package libcore

import (
	"fmt"
	"net"
	"os"
	"syscall"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

// IcmpPing sends one echo request to an IP address and returns the round-trip time in
// milliseconds. It uses an unprivileged ICMP datagram socket (Android opens
// net.ipv4.ping_group_range to every group) and protects the socket like every other libcore
// socket, so the probe leaves through the underlying network while the VPN is up.
func IcmpPing(address string, timeout int32) (latency int32, err error) {
	defer deferPanicToError("IcmpPing", func(err_ error) { err = err_ })

	ip := net.ParseIP(address)
	if ip == nil {
		return 0, fmt.Errorf("not an IP address: %s", address)
	}
	network, listen, protocol := "udp4", "0.0.0.0", ipv4.ICMPTypeEchoReply.Protocol()
	var echo, reply icmp.Type = ipv4.ICMPTypeEcho, ipv4.ICMPTypeEchoReply
	if ip.To4() == nil {
		network, listen, protocol = "udp6", "::", ipv6.ICMPTypeEchoReply.Protocol()
		echo, reply = ipv6.ICMPTypeEchoRequest, ipv6.ICMPTypeEchoReply
	}

	conn, err := icmp.ListenPacket(network, listen)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	protectPacketConn(conn)

	request, err := (&icmp.Message{
		Type: echo,
		Code: 0,
		Body: &icmp.Echo{ID: os.Getpid() & 0xffff, Seq: echoSeq, Data: []byte("nekobox")},
	}).Marshal(nil)
	if err != nil {
		return 0, err
	}

	start := time.Now()
	if err = conn.SetDeadline(start.Add(time.Duration(timeout) * time.Millisecond)); err != nil {
		return 0, err
	}
	if _, err = conn.WriteTo(request, &net.UDPAddr{IP: ip}); err != nil {
		return 0, err
	}
	// The kernel rewrites the echo ID on datagram ICMP sockets, so the sequence is the only
	// field that ties a reply to this request; anything else on the socket is skipped until
	// the deadline, except errors about the destination, which end the probe.
	buffer := make([]byte, 1500)
	for {
		n, _, err := conn.ReadFrom(buffer)
		if err != nil {
			return 0, err
		}
		elapsed := time.Since(start)
		message, err := icmp.ParseMessage(protocol, buffer[:n])
		if err != nil {
			continue
		}
		switch body := message.Body.(type) {
		case *icmp.Echo:
			if message.Type == reply && body.Seq == echoSeq {
				return int32(elapsed.Milliseconds()), nil
			}
		case *icmp.DstUnreach, *icmp.TimeExceeded:
			return 0, fmt.Errorf("unreachable: %v", message.Type)
		}
	}
}

const echoSeq = 1

// protectPacketConn hands the socket to the platform so it bypasses the VPN; failures only mean
// there is no VPN to bypass.
func protectPacketConn(conn *icmp.PacketConn) {
	var inner net.PacketConn
	if v4 := conn.IPv4PacketConn(); v4 != nil {
		inner = v4.PacketConn
	} else if v6 := conn.IPv6PacketConn(); v6 != nil {
		inner = v6.PacketConn
	}
	syscallConn, ok := inner.(interface{ SyscallConn() (syscall.RawConn, error) })
	if !ok {
		return
	}
	rawConn, err := syscallConn.SyscallConn()
	if err != nil {
		return
	}
	_ = rawConn.Control(func(fd uintptr) {
		_ = newBoxPlatformInterfaceWrapper().AutoDetectInterfaceControl(int(fd))
	})
}
