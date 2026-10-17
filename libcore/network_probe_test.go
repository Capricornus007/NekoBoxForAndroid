package libcore

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/miekg/dns"
	M "github.com/sagernet/sing/common/metadata"
)

type probeTestDialer struct{ address string }

func (d probeTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if network == "udp" {
		return (&net.Dialer{}).DialContext(ctx, "udp", d.address)
	}
	client, server := net.Pipe()
	server.Close()
	return client, nil
}
func (d probeTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("unused")
}
func TestNetworkProbeValidatesDNSResponseWithoutLeakingAnswers(t *testing.T) {
	for _, mode := range []string{"valid", "nxdomain", "wrong-id", "wrong-question", "not-dns"} {
		t.Run(mode, func(t *testing.T) {
			server, err := net.ListenPacket("udp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			go func() {
				_ = server.SetDeadline(time.Now().Add(time.Second))
				packet := make([]byte, 4096)
				n, source, err := server.ReadFrom(packet)
				if err != nil {
					return
				}
				query := new(dns.Msg)
				if query.Unpack(packet[:n]) != nil {
					return
				}
				answer := new(dns.Msg)
				answer.SetReply(query)
				switch mode {
				case "nxdomain":
					answer.Rcode = dns.RcodeNameError
				case "wrong-id":
					answer.Id++
				case "wrong-question":
					answer.Question[0].Name = "other.invalid."
				}
				payload, _ := answer.Pack()
				if mode == "not-dns" {
					payload = []byte("invalid")
				}
				_, _ = server.WriteTo(payload, source)
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			result := runNetworkProbe(ctx, probeTestDialer{server.LocalAddr().String()}, "example.org", netip.MustParseAddrPort("192.0.2.53:53"))
			if result["udp"] != "response" || result["tcp"] != "dialed" || result["tls"] != "failed" {
				t.Fatalf("unexpected transport status: %v", result)
			}
			valid := mode == "valid" || mode == "nxdomain"
			if (result["outbound_dns_udp"] == "response") != valid {
				t.Fatalf("accepted invalid DNS response: %v", result)
			}
			if mode == "nxdomain" && result["rcode"] != dns.RcodeNameError {
				t.Fatal("lost DNS rcode")
			}
			if _, ok := result["addresses"]; ok {
				t.Fatal("exported DNS answer addresses")
			}
		})
	}
}
func TestNetworkProbeRejectsInvalidInputBeforeStartingCore(t *testing.T) {
	b := new(BoxInstance)
	for _, domain := range []string{"", "https://example.org/token", "example.org:443", "bad\nname"} {
		if _, err := b.NetworkProbe("direct", domain, "1.1.1.1:53"); err == nil {
			t.Fatal("accepted invalid domain")
		}
	}
	if _, err := b.NetworkProbe("direct", "example.org", "resolver.example:53"); err == nil {
		t.Fatal("accepted DNS name instead of numeric IP")
	}
	if _, err := b.NetworkProbe("direct", "example.org", "1.1.1.1:53"); err == nil {
		t.Fatal("probed a stopped core")
	}
}
