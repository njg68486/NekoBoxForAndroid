package x365

import (
	"bytes"
	"encoding/hex"
	"net"
	"testing"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/gofrs/uuid/v5"
)

// Wire format (from 365VPN libgojni.so reverse engineering):
//
//	request:  [0:4] "X365" | [4] 0x01 | [5] cmd | [6:22] uuid16 |
//	          [22:24] port BE | [24] atyp (0x01 IPv4 / 0x02 domain / 0x03 IPv6) | [25:] addr
//	response: [0:4] "X365" | [4] consumed
//
// The address trailer is PORT-THEN-ADDRESS (vmess.AddressSerializer), NOT
// SOCKS order (atyp+addr+port). This test locks that in.
func TestRequestWireFormatDomain(t *testing.T) {
	userID := "5e33609f-b819-49c4-8521-6d7a232c2a20"
	key, err := uuid.FromString(userID)
	if err != nil {
		t.Fatal(err)
	}
	dest := M.ParseSocksaddr("www.google.com:443")

	var buf bytes.Buffer
	if err := WriteRequest(&buf, key, CommandTCP, dest, nil); err != nil {
		t.Fatal(err)
	}
	b := buf.Bytes()

	if len(b) < 25 {
		t.Fatalf("request too short: %d", len(b))
	}
	// magic + version + command
	if !bytes.Equal(b[0:4], []byte("X365")) {
		t.Fatalf("magic = %x, want 58333635", b[0:4])
	}
	if b[4] != 0x01 {
		t.Fatalf("version = %#x, want 0x01", b[4])
	}
	if b[5] != CommandTCP {
		t.Fatalf("command = %#x, want 0x01", b[5])
	}
	// uuid raw 16 bytes
	if !bytes.Equal(b[6:22], key[:]) {
		t.Fatalf("uuid mismatch")
	}
	// PORT FIRST, big endian, at offset 22
	port := uint16(b[22])<<8 | uint16(b[23])
	if port != 443 {
		t.Fatalf("port = %d, want 443 (port must precede atyp)", port)
	}
	// atyp at offset 24: domain = 0x02 (vmess encoding, NOT 0x03 SOCKS)
	if b[24] != 0x02 {
		t.Fatalf("atyp = %#x, want 0x02 (vmess domain)", b[24])
	}
	// domain length + "www.google.com"
	if b[25] != 14 || string(b[26:40]) != "www.google.com" {
		t.Fatalf("domain section wrong: %x", b[25:])
	}
}

func TestRequestWireFormatIPv4(t *testing.T) {
	key := [16]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}
	dest := M.ParseSocksaddr("1.1.1.1:8443")

	var buf bytes.Buffer
	if err := WriteRequest(&buf, key, CommandUDP, dest, nil); err != nil {
		t.Fatal(err)
	}
	b := buf.Bytes()

	if b[5] != CommandUDP {
		t.Fatalf("command = %#x, want 0x02", b[5])
	}
	port := uint16(b[22])<<8 | uint16(b[23])
	if port != 8443 {
		t.Fatalf("port = %d, want 8443", port)
	}
	if b[24] != 0x01 { // IPv4
		t.Fatalf("atyp = %#x, want 0x01", b[24])
	}
	if !bytes.Equal(b[25:29], net.IPv4(1, 1, 1, 1).To4()) {
		t.Fatalf("ipv4 mismatch: %v", b[25:29])
	}
}

func TestResponseValidation(t *testing.T) {
	// valid: X365 + 1 padding byte (consumed, not part of stream)
	r := bytes.NewReader([]byte{'X', '3', '6', '5', 0x00, 0xAA, 0xBB})
	if err := ReadResponse(r); err != nil {
		t.Fatalf("valid response rejected: %v", err)
	}
	// the 6th/7th bytes must remain readable
	rest := make([]byte, 2)
	n, err := r.Read(rest)
	if err != nil || n != 2 || rest[0] != 0xAA || rest[1] != 0xBB {
		t.Fatalf("stream shifted after response: %v %x", err, rest)
	}

	// invalid magic
	if err := ReadResponse(bytes.NewReader([]byte{'X', '3', '6', '4', 0x00})); err == nil {
		t.Fatal("invalid magic accepted")
	}
}

func TestConnRoundTrip(t *testing.T) {
	key := [16]byte{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9}
	dest := M.ParseSocksaddr("example.com:80")

	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	c := NewConn(client, key, CommandTCP, dest)
	go func() {
		// read the request the client sends
		head := make([]byte, 26+11+1) // magic..domain(len 11)+padding
		if _, err := server.Read(head); err != nil {
			return
		}
		if !bytes.Equal(head[0:4], []byte("X365")) {
			server.Write([]byte{'B', 'A', 'D', '9', 0x00})
			return
		}
		// reply: X365 + 1 consumed byte + payload
		server.Write([]byte{'X', '3', '6', '5', 0x01, 'H', 'I'})
	}()

	if _, err := c.Write([]byte{}); err != nil {
		t.Fatal(err)
	}
	// write real payload after header
	if _, err := c.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, 2)
	if _, err := c.Read(out); err != nil {
		t.Fatal(err)
	}
	if string(out) != "HI" {
		t.Fatalf("payload = %q, want HI", out)
	}
}

func TestPacketConnFrame(t *testing.T) {
	key := [16]byte{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
	dest := M.ParseSocksaddr("8.8.8.8:53")

	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	pc := &PacketConn{Conn: client, key: key, destination: dest}
	go func() {
		buf := make([]byte, 1024)
		n, _ := server.Read(buf)
		// first write = header + 2-byte length prefix + payload
		if !bytes.Equal(buf[0:4], []byte("X365")) {
			return
		}
		if buf[5] != CommandUDP {
			return
		}
		// find length prefix right after address: port(2)+atyp(1)+ipv4(4)
		trailerStart := 22
		length := int(buf[trailerStart+7])<<8 | int(buf[trailerStart+8])
		if length != 3 || string(buf[trailerStart+9:trailerStart+9+length]) != "abc" {
			server.Write([]byte("XX"))
			return
		}
		server.Write([]byte{'X', '3', '6', '5', 0x00, 0x00, 0x02, 'o', 'k'})
	}()

	if _, err := pc.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, 64)
	n, _, err := pc.ReadFrom(out)
	if err != nil {
		t.Fatal(err)
	}
	if string(out[:n]) != "ok" {
		t.Fatalf("packet = %q, want ok", out[:n])
	}
	_ = hexdump
}

// hex helpers used in debugging
func hexdump(b []byte) string { return hex.EncodeToString(b) }
