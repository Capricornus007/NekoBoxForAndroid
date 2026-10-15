package libcore

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
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
	for _, mode := range []string{"WireGuard", "AmneziaWG", "AmneziaWG2", "AmneziaWG3", "AmneziaWG31"} {
		t.Run(mode, func(t *testing.T) {
			ctx := service.ContextWithDefaultRegistry(context.Background())
			ctx = pause.WithDefaultManager(ctx)
			var keys [2]*ecdh.PrivateKey
			var sockets [2]net.PacketConn
			var peers [2]*wg.Endpoint
			addresses := []netip.Prefix{netip.MustParsePrefix("10.0.0.1/32"), netip.MustParsePrefix("10.0.0.2/32")}
			addresses6 := []netip.Prefix{netip.MustParsePrefix("fd00::1/128"), netip.MustParsePrefix("fd00::2/128")}
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
				options := wg.EndpointOptions{Context: ctx, Logger: log.NewNOPFactory().NewLogger("test"), Dialer: wgTestDialer{sockets[i]}, MTU: 1408, Address: []netip.Prefix{addresses[i], addresses6[i]}, PrivateKey: base64.StdEncoding.EncodeToString(keys[i].Bytes()), Peers: []wg.PeerOptions{{Endpoint: M.SocksaddrFromNet(sockets[1-i].LocalAddr()), PublicKey: base64.StdEncoding.EncodeToString(keys[1-i].PublicKey().Bytes()), AllowedIPs: []netip.Prefix{addresses[1-i], addresses6[1-i]}}}}
				// Keepalive is enabled on the client; the responder stays passive.
				// Two simultaneous initiators may need WireGuard's 5s+jitter retry.
				if i == 0 {
					options.Peers[0].PersistentKeepaliveInterval = "1"
				}
				if mode != "WireGuard" {
					options.Jc, options.Jmin, options.Jmax = 2, 32, 64
					options.S1, options.S2 = 15, 18
					options.H1, options.H2, options.H3, options.H4 = "123456-123500", "67543-67550", "123123-123200", "32345-32350"
				}
				if mode == "AmneziaWG2" || mode == "AmneziaWG3" || mode == "AmneziaWG31" {
					options.S3, options.S4 = 20, 25
					options.I1, options.I2, options.I3, options.I4, options.I5 = "<b 0x01020304>", "<r 16>", "<rc 8>", "<rd 8>", "<t>"
				}
				if mode == "AmneziaWG3" || mode == "AmneziaWG31" {
					protected := make([]byte, 32)
					for n := range protected {
						protected[n] = byte(n + 1)
					}
					options.HeaderProtectionKey = base64.StdEncoding.EncodeToString(protected)
					options.ContentPaddingAddition = "0-96"
					options.RekeyAfterTime = "100-120"
					options.RekeyTimeout = "1-2"
					options.RejectAfterTime = "180-200"
					options.KeepaliveTimeout = "10-15"
					options.MaxHandshakeAttempts = "10-18"
					if i == 0 {
						options.Peers[0].PersistentKeepaliveInterval = "1-2"
					}
				}
				if mode == "AmneziaWG31" {
					options.RandomTrailers = true
					options.DisableCookies = true
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
			path := &wgPacketReturn{make(chan []byte, 4)}
			if err := peers[0].AttachReturn(path); err != nil {
				t.Fatal(err)
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
				_ = listener.SetDeadline(time.Now().Add(8 * time.Second))
				_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
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
			_ = peers[0].DetachReturn(path)
			testWireGuardIPPackets(t, peers, addresses, addresses6)
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

func TestInvalidAmneziaIPCDoesNotBlockStartup(t *testing.T) {
	ctx := service.ContextWithDefaultRegistry(context.Background())
	ctx = pause.WithDefaultManager(ctx)
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = socket.Close() })
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	endpoint, err := wg.NewEndpoint(wg.EndpointOptions{
		Context: ctx, Logger: log.NewNOPFactory().NewLogger("test"),
		Dialer: wgTestDialer{socket}, MTU: 1280,
		Address:    []netip.Prefix{netip.MustParsePrefix("10.0.0.1/32")},
		PrivateKey: base64.StdEncoding.EncodeToString(key.Bytes()),
		// The CPS grammar is validated by the upstream device's IPC parser.
		I1: "<unknown>",
	})
	if err != nil {
		t.Fatal(err)
	}
	result := make(chan error, 1)
	go func() { result <- endpoint.Start(false) }()
	select {
	case err = <-result:
		if err == nil {
			_ = endpoint.Close()
			t.Fatal("invalid CPS was accepted")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("IPC failure blocked device shutdown")
	}
}

func TestAmnezia31OptionsDecodeForOutboundAndEndpoint(t *testing.T) {
	for _, modern := range []bool{false, true} {
		for _, keepalive := range []any{25, "22-30"} {
			key := base64.StdEncoding.EncodeToString(make([]byte, 32))
			header := base64.StdEncoding.EncodeToString([]byte("01234567890123456789012345678901"))
			peer := map[string]any{"public_key": key, "allowed_ips": []string{"0.0.0.0/0"}, "persistent_keepalive_interval": keepalive}
			endpoint := map[string]any{"type": "wireguard", "tag": "wg", "private_key": key, "header_protection_key": header, "s1": 12, "s2": 13, "s3": 14, "s4": 15, "content_padding_addition": "0-96", "rekey_after_time": "100-120", "rekey_timeout": "5-8", "reject_after_time": "180-200", "keepalive_timeout": "10-15", "max_handshake_attempts": "10-18", "random_trailers": true, "disable_cookies": true, "peers": []any{peer}}
			root := map[string]any{}
			if modern {
				endpoint["address"] = []string{"10.0.0.2/32"}
				peer["address"] = "127.0.0.1"
				peer["port"] = 51820
				root["endpoints"] = []any{endpoint}
			} else {
				endpoint["local_address"] = []string{"10.0.0.2/32"}
				peer["server"] = "127.0.0.1"
				peer["server_port"] = 51820
				root["outbounds"] = []any{endpoint}
			}
			content, _ := json.Marshal(root)
			if err := CheckConfig(string(content), nil); err != nil {
				t.Fatalf("modern=%v keepalive=%v: %v", modern, keepalive, err)
			}
		}
	}
}

func TestInvalidAmnezia31ParametersDoNotExposeHeaderKey(t *testing.T) {
	for _, options := range []wg.EndpointOptions{
		{HeaderProtectionKey: "private-fixture-key"},
		{ContentPaddingAddition: "0-4294967295"}, {RekeyAfterTime: "30-20"},
		{RekeyTimeout: "1\nprivate_key=wrong"}, {Peers: []wg.PeerOptions{{PersistentKeepaliveInterval: "-1"}}},
	} {
		_, err := wg.NewEndpoint(options)
		if err == nil || strings.Contains(err.Error(), "missing private key") || strings.Contains(err.Error(), "private-fixture-key") {
			t.Fatalf("invalid AWG3 input or secret disclosure: %v", err)
		}
	}
}
