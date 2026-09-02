package xhttp

// xstream cipher layer for the Heysocks (黑石 VPN) OFFICIAL "xhttp" protocol.
//
// Reverse-engineered & VERIFIED end-to-end (hsks-5.2.8, 2026-08-27):
//   * decrypted 11/11 captured C2S first packets to real SOCKS addrs
//   * replayed a synthesized first packet to the live gateway and decoded a
//     real "HTTP/1.1 204 No Content" upstream response.
//
// KEY DERIVATION (decisive):
//   AES-128 key = EVP_BytesToKey(MD5, secret = key1 ASCII STRING, keyLen=16)
//     key1 is the fixed 32-char hex STRING "177fd0…" fed as its literal ASCII
//     bytes (NOT hex-decoded), identical for every node:
//       kdfMD5([]byte("177fd09cb1d8211b9649f0216d97d7d3"),16)
//       = 0a8583f039b887cad22ad535d06efc66
//
// WIRE LAYOUT — request AND response share the SAME framing:
//   [0:68]   sess    session-fixed prelude (server-issued auth material)
//   [68:128] rand60  per-connection random (server ignores this region)
//   [128:144] MAGIC  62d77075106053a2ee4faf9589f9a206
//   [144]    tokLen  length of the auth token that follows
//   [145:145+tokLen] TOKEN  server-VALIDATED runtime auth token
//   [+0]     ivLen=16, [+1:+17] IV, [+17:] AES-128-CTR(SocksAddr(dst)||payload)
//
// The server validates `sess`+`token`; supply them via config
// (`sess` + `auth` fields).

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"encoding/hex"
	"net"
	"strconv"
	"strings"
	"sync"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
)

const (
	ivSize      = 16
	keySize     = 16 // AES-128
	magicOffset = 128
	sessLen     = 68
	rand60Len   = 60
)

// magic is the global 16-byte protocol fingerprint at offset 128 of the first
// packet (and somewhere in the server response prefix).
var magic = []byte{
	0x62, 0xd7, 0x70, 0x75, 0x10, 0x60, 0x53, 0xa2,
	0xee, 0x4f, 0xaf, 0x95, 0x89, 0xf9, 0xa2, 0x06,
}

// kdfMD5 reproduces core.Kdf: OpenSSL EVP_BytesToKey with MD5, empty salt.
func kdfMD5(secret []byte, keyLen int) []byte {
	var out []byte
	var prev []byte
	for len(out) < keyLen {
		h := md5.New()
		h.Write(prev)
		h.Write(secret)
		prev = h.Sum(nil)
		out = append(out, prev...)
	}
	return out[:keyLen]
}

// StreamCipher holds the derived AES key for a node.
type StreamCipher struct {
	key []byte // 16 bytes
}

// NewStreamCipher derives the AES key from key1's ASCII string. password may be
// empty ("" -> use the fixed default key1) or "key1[:key2]" (key2 ignored here).
func NewStreamCipher(password string) (*StreamCipher, error) {
	key1 := "177fd09cb1d8211b9649f0216d97d7d3"
	if password != "" {
		parts := strings.SplitN(password, ":", 2)
		if parts[0] != "" {
			key1 = parts[0]
		}
	}
	return &StreamCipher{key: kdfMD5([]byte(key1), keySize)}, nil
}

func decodeHex(s string) []byte {
	if s == "" {
		return nil
	}
	b, err := hex.DecodeString(strings.TrimSpace(s))
	if err != nil {
		return nil
	}
	return b
}

func randBytes(n int) []byte {
	if n <= 0 {
		return nil
	}
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return b
}

func newCTR(key, iv []byte) (cipher.Stream, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewCTR(block, iv), nil
}

// conn wraps a base byte stream with the xstream AES-CTR cipher.
// Reader/Writer are NEVER replaceable: the CTR state must live for the whole
// connection (FlClash unwrapper bug lesson, 2026-08-29).
type conn struct {
	net.Conn
	cipher      *StreamCipher
	destination M.Socksaddr
	sess        []byte // [0:68] session prelude (from auth material)
	token       []byte // server-validated auth token (from auth material)

	writeMu      sync.Mutex
	writeStarted bool
	writeStream  cipher.Stream

	readMu       sync.Mutex
	readStarted  bool
	readStream   cipher.Stream
	readBuf      []byte // accumulates response bytes until MAGIC prefix is parsed
	pending      []byte // decrypted plaintext not yet delivered to caller
}

// ClientConn builds a stream. sess must be 68 bytes, token is the runtime auth
// token (variable length). If sess is short it is right-padded with random bytes.
func (c *StreamCipher) ClientConn(base net.Conn, destination M.Socksaddr, sess, token []byte) net.Conn {
	s := make([]byte, sessLen)
	copy(s, sess)
	if len(sess) < sessLen {
		copy(s[len(sess):], randBytes(sessLen-len(sess)))
	}
	return &conn{
		Conn:        base,
		cipher:      c,
		destination: destination,
		sess:        s,
		token:       token,
	}
}

// buildPrefix assembles the plaintext prefix that precedes the ciphertext:
//
//	sess(68) | rand60 | MAGIC | tokLen | token | ivLen | IV
func (c *conn) buildPrefix(iv []byte) []byte {
	out := make([]byte, 0, sessLen+rand60Len+16+1+len(c.token)+1+ivSize)
	out = append(out, c.sess...)                 // [0:68]
	out = append(out, randBytes(rand60Len)...)   // [68:128] server ignores
	out = append(out, magic...)                  // [128:144]
	out = append(out, byte(len(c.token)))        // [144]
	out = append(out, c.token...)                // [145:145+tokLen]
	out = append(out, byte(ivSize))              // ivLen
	out = append(out, iv...)                     // IV
	return out
}

// buildHead serializes the SOCKS destination (VMess-style AddrPort).
func (c *conn) buildHead() ([]byte, error) {
	b := buf.New()
	defer b.Release()
	if err := M.SocksaddrSerializer.WriteAddrPort(b, c.destination); err != nil {
		return nil, err
	}
	head := make([]byte, b.Len())
	copy(head, b.Bytes())
	return head, nil
}

func (c *conn) initWrite() error {
	iv := make([]byte, ivSize)
	if _, err := rand.Read(iv); err != nil {
		return err
	}
	stream, err := newCTR(c.cipher.key, iv)
	if err != nil {
		return err
	}
	prefix := c.buildPrefix(iv)
	if _, err = c.Conn.Write(prefix); err != nil {
		return err
	}
	c.writeStream = stream
	c.writeStarted = true

	head, err := c.buildHead()
	if err != nil {
		return err
	}
	enc := make([]byte, len(head))
	c.writeStream.XORKeyStream(enc, head)
	_, err = c.Conn.Write(enc)
	return err
}

func (c *conn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if !c.writeStarted {
		if err := c.initWrite(); err != nil {
			return 0, err
		}
	}
	if len(p) == 0 {
		return 0, nil
	}
	enc := make([]byte, len(p))
	c.writeStream.XORKeyStream(enc, p)
	if _, err := c.Conn.Write(enc); err != nil {
		return 0, err
	}
	return len(p), nil
}

// initReadFromBuf finds MAGIC in the accumulated response, skips
// [prefix][MAGIC][tokLen][token][ivLen][IV], installs the read stream, and
// returns any leftover ciphertext that followed the IV. Returns (nil,false)
// when not enough bytes have arrived yet.
func (c *conn) initReadFromBuf() ([]byte, bool) {
	idx := indexOf(c.readBuf, magic)
	if idx < 0 {
		return nil, false
	}
	p := idx + len(magic)
	if p >= len(c.readBuf) {
		return nil, false
	}
	tokLen := int(c.readBuf[p])
	p++
	p += tokLen // skip token
	if p >= len(c.readBuf) {
		return nil, false
	}
	ivLen := int(c.readBuf[p])
	p++
	if ivLen != ivSize {
		return nil, false
	}
	if p+ivLen > len(c.readBuf) {
		return nil, false
	}
	iv := c.readBuf[p : p+ivLen]
	p += ivLen
	stream, err := newCTR(c.cipher.key, iv)
	if err != nil {
		return nil, false
	}
	c.readStream = stream
	c.readStarted = true
	leftover := append([]byte(nil), c.readBuf[p:]...)
	c.readBuf = nil
	return leftover, true
}

func (c *conn) Read(p []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()

	// Deliver any buffered plaintext first.
	if len(c.pending) > 0 {
		m := copy(p, c.pending)
		c.pending = c.pending[m:]
		return m, nil
	}

	if !c.readStarted {
		// Keep reading until the MAGIC-prefixed response header is complete.
		for {
			tmp := make([]byte, 65536)
			n, err := c.Conn.Read(tmp)
			if n > 0 {
				c.readBuf = append(c.readBuf, tmp[:n]...)
				if leftover, ok := c.initReadFromBuf(); ok {
					if len(leftover) > 0 {
						dec := make([]byte, len(leftover))
						c.readStream.XORKeyStream(dec, leftover)
						m := copy(p, dec)
						if m < len(dec) {
							c.pending = dec[m:]
						}
						return m, nil
					}
					break // header consumed, no trailing data yet
				}
			}
			if err != nil {
				return 0, err
			}
		}
	}

	n, err := c.Conn.Read(p)
	if n > 0 {
		c.readStream.XORKeyStream(p[:n], p[:n])
	}
	return n, err
}

// ReaderReplaceable / WriterReplaceable are permanently false: the AES-CTR
// state spans the whole connection life. Letting sing's high-speed pipe
// UnwrapReader/UnwrapWriter bypass the cipher breaks every real TLS/MTProto
// flight after the first packet (the FlClash xhttp disconnect bug).
func (c *conn) ReaderReplaceable() bool { return false }
func (c *conn) WriterReplaceable() bool { return false }

func (c *conn) NeedAdditionalReadDeadline() bool { return false }

func (c *conn) Upstream() any { return c.Conn }

// indexOf returns the first index of sub in b, or -1.
func indexOf(b, sub []byte) int {
	if len(sub) == 0 || len(b) < len(sub) {
		return -1
	}
	for i := 0; i+len(sub) <= len(b); i++ {
		if b[i] == sub[0] {
			match := true
			for j := 1; j < len(sub); j++ {
				if b[i+j] != sub[j] {
					match = false
					break
				}
			}
			if match {
				return i
			}
		}
	}
	return -1
}

var _ = magicOffset // kept for documentation
var _ = strconv.Itoa
