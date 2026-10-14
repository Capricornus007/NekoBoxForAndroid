package libcore

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	wg "github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type wgTestDialer struct{ packet net.PacketConn }

func (d wgTestDialer) DialContext(_ context.Context, _ string, destination M.Socksaddr) (net.Conn, error) {
	return bufio.NewBindPacketConn(d.packet, destination.UDPAddr()), nil
}
func (d wgTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return d.packet, nil
}

func TestWireGuardAndAmneziaPeersExchangePackets(t *testing.T) {
	for _, mode := range []string{"WireGuard", "AmneziaWG", "AmneziaWG2"} {
		t.Run(mode, func(t *testing.T) {
			ctx := service.ContextWithDefaultRegistry(context.Background())
			ctx = pause.WithDefaultManager(ctx)
			var keys [2]*ecdh.PrivateKey
			var sockets [2]net.PacketConn
			var peers [2]*wg.Endpoint
			addresses := []netip.Prefix{netip.MustParsePrefix("10.0.0.1/32"), netip.MustParsePrefix("10.0.0.2/32")}
			for i := range keys {
				var err error
				keys[i], err = ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil {
					t.Fatal(err)
				}
				sockets[i], err = net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = sockets[i].Close() })
			}
			for i := range peers {
				options := wg.EndpointOptions{Context: ctx, Logger: log.NewNOPFactory().NewLogger("test"), Dialer: wgTestDialer{sockets[i]}, MTU: 1408, Address: []netip.Prefix{addresses[i]}, PrivateKey: base64.StdEncoding.EncodeToString(keys[i].Bytes()), Peers: []wg.PeerOptions{{Endpoint: M.SocksaddrFromNet(sockets[1-i].LocalAddr()), PublicKey: base64.StdEncoding.EncodeToString(keys[1-i].PublicKey().Bytes()), AllowedIPs: []netip.Prefix{addresses[1-i]}}}}
				// Keepalive is enabled on the client; the responder stays passive.
				// Two simultaneous initiators may need WireGuard's 5s+jitter retry.
				if i == 0 {
					options.Peers[0].PersistentKeepaliveInterval = 1
				}
				if mode != "WireGuard" {
					options.Jc, options.Jmin, options.Jmax = 2, 32, 64
					options.S1, options.S2 = 15, 18
					options.H1, options.H2, options.H3, options.H4 = "123456-123500", "67543-67550", "123123-123200", "32345-32350"
				}
				if mode == "AmneziaWG2" {
					options.S3, options.S4 = 20, 25
					options.I1, options.I2, options.I3, options.I4, options.I5 = "<b 0x01020304>", "<r 16>", "<rc 8>", "<rd 8>", "<t>"
				}
				var err error
				peers[i], err = wg.NewEndpoint(options)
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = peers[i].Close() })
				if err = peers[i].Start(false); err != nil {
					t.Fatal(err)
				}
			}
			for sender := range peers {
				receiver := 1 - sender
				listener, err := peers[receiver].ListenPacket(ctx, M.Socksaddr{Addr: addresses[receiver].Addr()})
				if err != nil {
					t.Fatal(err)
				}
				destination := M.Socksaddr{Addr: addresses[receiver].Addr(), Port: M.SocksaddrFromNet(listener.LocalAddr()).Port}
				conn, err := peers[sender].DialContext(ctx, "udp", destination)
				if err != nil {
					t.Fatal(err)
				}
				_ = listener.SetDeadline(time.Now().Add(5 * time.Second))
				_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
				if _, err = conn.Write([]byte("payload")); err != nil {
					t.Fatal(err)
				}
				buffer := make([]byte, 128)
				n, source, err := listener.ReadFrom(buffer)
				if err != nil {
					t.Fatal(err)
				}
				if string(buffer[:n]) != "payload" {
					t.Fatal("tunnel changed payload")
				}
				if _, err = listener.WriteTo([]byte("reply"), source); err != nil {
					t.Fatal(err)
				}
				n, err = conn.Read(buffer)
				if err != nil {
					t.Fatal(err)
				}
				if string(buffer[:n]) != "reply" {
					t.Fatal("tunnel reply changed")
				}
				_ = conn.Close()
				_ = listener.Close()
			}
		})
	}
}

func TestInvalidAmneziaParametersFailBeforeHandshake(t *testing.T) {
	for _, options := range []wg.EndpointOptions{{Jc: -1}, {Jc: 1, Jmin: 100, Jmax: 50}, {S4: -1}, {I1: "<t>\nprivate_key=wrong"}} {
		if _, err := wg.NewEndpoint(options); err == nil || strings.Contains(err.Error(), "missing private key") {
			t.Fatalf("did not validate AWG parameters first: %v", err)
		}
	}
}
