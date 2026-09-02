package main

// Standalone wire-format verifier for the x365 protocol (run outside go test
// because PRoot crashes on go test's fork chain).
import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os"

	"github.com/gofrs/uuid/v5"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"

	"libcore/protocol/x365"
)

func fail(format string, a ...any) {
	fmt.Printf("FAIL: "+format+"\n", a...)
	os.Exit(1)
}

func main() {
	// --- domain destination: www.google.com:443 ---
	userID := "5e33609f-b819-49c4-8521-6d7a232c2a20"
	key, _ := uuid.FromString(userID)
	dest := M.ParseSocksaddr("www.google.com:443")

	buffer := buf.New()
	defer buffer.Release()
	// replicate Conn.writeRequest encoding path
	if err := x365.WriteRequestEncodeForTest(buffer, key, x365.CommandTCP, dest); err != nil {
		fail("encode: %v", err)
	}
	b := buffer.Bytes()

	if !bytes.Equal(b[0:4], []byte("X365")) {
		fail("magic %x", b[0:4])
	}
	if b[4] != 0x01 {
		fail("version %#x", b[4])
	}
	if b[5] != 0x01 {
		fail("command %#x", b[5])
	}
	if !bytes.Equal(b[6:22], key[:]) {
		fail("uuid mismatch")
	}
	port := binary.BigEndian.Uint16(b[22:24])
	if port != 443 {
		fail("port %d (must sit at offset 22, BEFORE atyp)", port)
	}
	if b[24] != 0x02 {
		fail("atyp %#x (vmess domain=0x02, not SOCKS 0x03)", b[24])
	}
	if b[25] != 14 || string(b[26:40]) != "www.google.com" {
		fail("domain section")
	}
	fmt.Printf("domain OK: %x\n", b)

	// --- IPv4 destination ---
	dest4 := M.ParseSocksaddr("1.1.1.1:8443")
	buffer2 := buf.New()
	defer buffer2.Release()
	if err := x365.WriteRequestEncodeForTest(buffer2, [16]byte{1}, x365.CommandUDP, dest4); err != nil {
		fail("encode4: %v", err)
	}
	b4 := buffer2.Bytes()
	if b4[5] != 0x02 {
		fail("udp command %#x", b4[5])
	}
	if binary.BigEndian.Uint16(b4[22:24]) != 8443 {
		fail("ipv4 port")
	}
	if b4[24] != 0x01 {
		fail("ipv4 atyp %#x", b4[24])
	}
	if !bytes.Equal(b4[25:29], []byte{1, 1, 1, 1}) {
		fail("ipv4 addr %v", b4[25:29])
	}
	fmt.Printf("ipv4 OK: %x\n", b4)

	// --- response validation consumes 5 bytes ---
	r := bytes.NewReader([]byte{'X', '3', '6', '5', 0x00, 0xAA, 0xBB})
	if err := x365.ReadResponse(r); err != nil {
		fail("valid response rejected: %v", err)
	}
	rest := make([]byte, 2)
	if _, err := r.Read(rest); err != nil || rest[0] != 0xAA || rest[1] != 0xBB {
		fail("stream shifted after response read: %v %x", err, rest)
	}
	fmt.Println("response OK: 5 bytes consumed, stream aligned")

	fmt.Println("ALL WIRE FORMAT CHECKS PASSED")
}
