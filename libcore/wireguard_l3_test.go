package libcore

import (
	"context"
	"encoding/binary"
	"net/netip"
	"testing"
	"time"

	wg "github.com/sagernet/sing-box/transport/wireguard"
)

type wgPacketReturn struct{ packets chan []byte }

func (*wgPacketReturn) ReturnHeadroom() int { return 64 }
func (r *wgPacketReturn) ReturnPackets(packets [][]byte) [][]byte {
	remaining := make([][]byte, 0)
	for _, packet := range packets {
		data := packet[64:]
		if (data[0]>>4 == 4 && data[9] != 1) || (data[0]>>4 == 6 && data[6] != 58) {
			remaining = append(remaining, packet)
			continue
		}
		owned := append([]byte(nil), packet[64:]...)
		select {
		case r.packets <- owned:
		default:
		}
	}
	return remaining
}
func ipChecksum(data []byte) uint16 {
	var sum uint32
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data))
		data = data[2:]
	}
	if len(data) > 0 {
		sum += uint32(data[0]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 65535) + (sum >> 16)
	}
	return ^uint16(sum)
}
func wgEchoPacket(source, destination netip.Addr) []byte {
	body := []byte{8, 0, 0, 0, 0x12, 0x34, 0, 1, 't', 'e', 's', 't'}
	if source.Is4() {
		packet := make([]byte, 20+len(body))
		packet[0] = 0x45
		binary.BigEndian.PutUint16(packet[2:], uint16(len(packet)))
		packet[8] = 64
		packet[9] = 1
		s, d := source.As4(), destination.As4()
		copy(packet[12:], s[:])
		copy(packet[16:], d[:])
		binary.BigEndian.PutUint16(packet[10:], ipChecksum(packet[:20]))
		binary.BigEndian.PutUint16(body[2:], ipChecksum(body))
		copy(packet[20:], body)
		return packet
	}
	body[0] = 128
	packet := make([]byte, 40+len(body))
	packet[0] = 0x60
	binary.BigEndian.PutUint16(packet[4:], uint16(len(body)))
	packet[6] = 58
	packet[7] = 64
	s, d := source.As16(), destination.As16()
	copy(packet[8:], s[:])
	copy(packet[24:], d[:])
	pseudo := make([]byte, 40+len(body))
	copy(pseudo, s[:])
	copy(pseudo[16:], d[:])
	binary.BigEndian.PutUint32(pseudo[32:], uint32(len(body)))
	pseudo[39] = 58
	copy(pseudo[40:], body)
	binary.BigEndian.PutUint16(body[2:], ipChecksum(pseudo))
	copy(packet[40:], body)
	return packet
}
func testWireGuardIPPackets(t *testing.T, peers [2]*wg.Endpoint, addresses, addresses6 []netip.Prefix) {
	t.Helper()
	path := &wgPacketReturn{make(chan []byte, 4)}
	if err := peers[0].AttachReturn(path); err != nil {
		t.Fatal(err)
	}
	defer peers[0].DetachReturn(path)
	for _, pair := range [][]netip.Prefix{addresses, addresses6} {
		packet := wgEchoPacket(pair[0].Addr(), pair[1].Addr())
		if err := peers[0].WritePackets([][]byte{packet}); err != nil {
			t.Fatal(err)
		}
		select {
		case reply := <-path.packets:
			offset, kind := 20, byte(0)
			if pair[0].Addr().Is6() {
				offset, kind = 40, 129
			}
			if len(reply) != len(packet) || reply[offset] != kind || string(reply[offset+8:]) != "test" {
				t.Fatalf("invalid ICMP reply: %x", reply)
			}
		case <-time.After(8 * time.Second):
			t.Fatal("no encrypted ICMP echo reply")
		}
	}
	for _, destination := range []netip.Addr{addresses[1].Addr(), addresses6[1].Addr()} {
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		report := peers[0].ProbeMTU(ctx, destination)
		cancel()
		if report["state"] != "verified-inner-packet" || report["largest_verified"] != 1408 {
			t.Fatalf("MTU probe failed: %v", report)
		}
		// The existing TUN owner is still attached, and ordinary ICMP is not intercepted.
		if err := peers[0].AttachReturn(path); err != nil {
			t.Fatal("probe replaced TUN return owner")
		}
		packet := wgEchoPacket(addresses[0].Addr(), addresses[1].Addr())
		if err := peers[0].WritePackets([][]byte{packet}); err != nil {
			t.Fatal(err)
		}
		select {
		case <-path.packets:
		case <-time.After(3 * time.Second):
			t.Fatal("probe stole unrelated reply")
		}
	}
	if err := peers[0].WritePackets([][]byte{{0x45}}); err == nil {
		t.Fatal("accepted short packet")
	}
	if err := peers[0].WritePackets([][]byte{wgEchoPacket(addresses[0].Addr(), netip.MustParseAddr("192.0.2.1"))}); err == nil {
		t.Fatal("bypassed allowed IPs")
	}
	counters := peers[0].Diagnostics()
	stats := counters["peers"].([]map[string]any)
	if len(stats) != 1 || stats[0]["last_handshake_time_sec"].(uint64) == 0 || stats[0]["rx_bytes"].(uint64) == 0 || stats[0]["tx_bytes"].(uint64) == 0 {
		t.Fatal("missing live peer counters")
	}
}
