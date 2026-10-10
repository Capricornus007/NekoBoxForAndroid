package libcore

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/amneziawg"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
	"golang.org/x/crypto/curve25519"
)

func wireGuardTestKey(t *testing.T) (private, public string) {
	t.Helper()
	key := make([]byte, curve25519.ScalarSize)
	if _, err := rand.Read(key); err != nil {
		t.Fatal(err)
	}
	publicKey, err := curve25519.X25519(key, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(key), base64.StdEncoding.EncodeToString(publicKey)
}

// wireGuardServerDialer listens on a fixed loopback address, so the outbound under test knows
// where to send its handshake.
type wireGuardServerDialer struct{ address string }

func (d wireGuardServerDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, destination.String())
}

func (d wireGuardServerDialer) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", d.address)
}

func startWireGuardTestServer(t *testing.T, clientPublic string) (address, public string) {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address = conn.LocalAddr().String()
	conn.Close()
	private, public := wireGuardTestKey(t)
	server, err := amneziawg.NewEndpoint(amneziawg.EndpointOptions{
		Context:    pause.WithDefaultManager(context.Background()),
		Logger:     log.NewNOPFactory().Logger(),
		Dialer:     wireGuardServerDialer{address},
		Address:    []netip.Prefix{netip.MustParsePrefix("10.78.0.1/32")},
		PrivateKey: private,
		Peers: []amneziawg.PeerOptions{{
			PublicKey:  clientPublic,
			AllowedIPs: []netip.Prefix{netip.MustParsePrefix("10.78.0.2/32")},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close() })
	if err = server.Start(false); err != nil {
		t.Fatal(err)
	}
	return address, public
}

// newWireGuardTestBox builds, but does not start, a box whose "wg" outbound peers with a local
// server endpoint.
func newWireGuardTestBox(t *testing.T, outboundType string, keepalive int) (instance *box.Box, ctx context.Context, clientPrivate, serverAddress, serverPublic string) {
	t.Helper()
	clientPrivate, clientPublic := wireGuardTestKey(t)
	serverAddress, serverPublic = startWireGuardTestServer(t, clientPublic)
	server := M.ParseSocksaddr(serverAddress)
	config := fmt.Sprintf(`{
  "log": {"disabled": true},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {
      "type": %q, "tag": "wg", "local_address": ["10.78.0.2/32"], "private_key": %q,
      "peers": [{
        "server": %q, "server_port": %d, "public_key": %q,
        "allowed_ips": ["10.78.0.0/24"], "persistent_keepalive_interval": %d
      }]
    }
  ]
}`, outboundType, clientPrivate, server.AddrString(), server.Port, serverPublic, keepalive)
	ctx = box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	return instance, ctx, clientPrivate, serverAddress, serverPublic
}

func TestWireGuardStatusReportsAppliedPeerSettings(t *testing.T) {
	for _, outboundType := range []string{"wireguard", "amneziawg"} {
		t.Run(outboundType, func(t *testing.T) {
			instance, ctx, clientPrivate, serverAddress, serverPublic := newWireGuardTestBox(t, outboundType, 1)
			var err error
			boxInstance := &BoxInstance{Box: instance, ctx: ctx}
			if _, err = WireGuardStatus(boxInstance, "wg"); err == nil || err.Error() != "wireguard:not-running" {
				t.Fatalf("status before start: %v", err)
			}
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			boxInstance.running = true

			// No traffic is routed through the outbound, so only the applied keepalive can
			// start the handshake.
			var peer wireGuardPeerStatus
			deadline := time.Now().Add(10 * time.Second)
			for {
				raw, err := WireGuardStatus(boxInstance, "wg")
				if err != nil {
					t.Fatal(err)
				}
				if strings.Contains(raw, clientPrivate) {
					t.Fatal("status leaked the private key")
				}
				var status wireGuardStatusJSON
				if err = json.Unmarshal([]byte(raw), &status); err != nil {
					t.Fatal(err)
				}
				if len(status.Peers) != 1 {
					t.Fatalf("peers: %s", raw)
				}
				peer = status.Peers[0]
				if peer.LastHandshake != 0 {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("no handshake")
				}
				time.Sleep(50 * time.Millisecond)
			}
			if peer.PublicKey != serverPublic || peer.Endpoint != serverAddress || peer.PersistentKeepalive != 1 {
				t.Fatalf("unexpected peer: %+v", peer)
			}
			if len(peer.AllowedIPs) != 1 || peer.AllowedIPs[0] != "10.78.0.0/24" {
				t.Fatalf("allowed IPs: %v", peer.AllowedIPs)
			}
			if age := time.Since(time.UnixMilli(peer.LastHandshake)); age < 0 || age > time.Minute {
				t.Fatalf("handshake age %s", age)
			}
			for tag, want := range map[string]string{"direct": "wireguard:not-found", "missing": "wireguard:not-found"} {
				if _, err = WireGuardStatus(boxInstance, tag); err == nil || err.Error() != want {
					t.Fatalf("%s: %v", tag, err)
				}
			}
			boxInstance.running = false
			if _, err = WireGuardStatus(boxInstance, "wg"); err == nil || err.Error() != "wireguard:not-running" {
				t.Fatalf("status after stop: %v", err)
			}
		})
	}
}

// Reads racing Close either see the live device or report that the instance stopped.
func TestWireGuardStatusDuringClose(t *testing.T) {
	instance, ctx, _, _, _ := newWireGuardTestBox(t, "wireguard", 0)
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	boxInstance := &BoxInstance{Box: instance, ctx: ctx, running: true}
	failures := make(chan error, 8)
	var readers sync.WaitGroup
	for range 4 {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for {
				_, err := WireGuardStatus(boxInstance, "wg")
				if err == nil {
					continue
				}
				if err.Error() != "wireguard:not-running" {
					failures <- err
				}
				return
			}
		}()
	}
	time.Sleep(20 * time.Millisecond)
	if err := boxInstance.Close(); err != nil {
		t.Fatal(err)
	}
	readers.Wait()
	close(failures)
	for err := range failures {
		t.Fatal(err)
	}
}
