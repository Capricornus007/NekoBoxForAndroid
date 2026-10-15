package libcore

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// NetworkProbe is explicit and bounded. It uses the running core's protected
// dialers; Android's process-default network must never bypass its socket policy.
func (b *BoxInstance) NetworkProbe(tag, domain, dnsAddress string) (string, error) {
	domain = strings.TrimSuffix(strings.TrimSpace(domain), ".")
	if len(domain) == 0 || len(domain) > 253 || strings.ContainsAny(domain, "/:@\\ \t\r\n") {
		return "", fmt.Errorf("invalid probe domain")
	}
	if _, ok := dns.IsDomainName(domain); !ok {
		return "", fmt.Errorf("invalid probe domain")
	}
	destination, err := netip.ParseAddrPort(dnsAddress)
	if err != nil || destination.Port() == 0 || destination.Addr().IsUnspecified() {
		return "", fmt.Errorf("DNS target must be IP:port")
	}
	b.access.Lock()
	if b.state != 1 {
		b.access.Unlock()
		return "", fmt.Errorf("core is not running")
	}
	outbound, exists := b.Outbound().Outbound(tag)
	if !exists {
		if manager := service.FromContext[adapter.EndpointManager](b.ctx); manager != nil {
			for _, endpoint := range manager.Endpoints() {
				if endpoint.Tag() == tag {
					outbound, exists = endpoint, true
					break
				}
			}
		}
	}
	if !exists {
		b.access.Unlock()
		return "", fmt.Errorf("outbound is unavailable")
	}
	// Close cancels the core context and waits for these operations before closing devices.
	b.probeWG.Add(1)
	ctx, cancel := context.WithTimeout(b.ctx, 18*time.Second)
	b.access.Unlock()
	defer b.probeWG.Done()
	defer cancel()
	report := runNetworkProbe(ctx, outbound, domain, destination)
	// Resolve selectors at probe time; do not guess MTU from successful HTTP.
	resolved := outbound
	for depth := 0; depth < 16; depth++ {
		group, ok := resolved.(adapter.OutboundGroup)
		if !ok {
			break
		}
		next, ok := b.Outbound().Outbound(group.Now())
		if !ok {
			break
		}
		resolved = next
	}
	if prober, ok := resolved.(interface {
		ProbeMTU(context.Context, netip.Addr) map[string]any
	}); ok {
		report["mtu"] = prober.ProbeMTU(ctx, destination.Addr())
	} else {
		report["mtu"] = map[string]any{"state": "unsupported"}
	}
	content, err := json.Marshal(report)
	return string(content), err
}

func runNetworkProbe(ctx context.Context, dialer N.Dialer, domain string, destination netip.AddrPort) map[string]any {
	report := map[string]any{"outbound_dns_udp": "failed", "udp": "no-response", "tcp": "failed", "tls": "not-tested"}
	// A received UDP datagram proves a response path, even if it is not valid DNS.
	func() {
		probeCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		defer cancel()
		started := time.Now()
		conn, err := dialer.DialContext(probeCtx, N.NetworkUDP, M.SocksaddrFromNetIP(destination))
		if err != nil {
			return
		}
		defer conn.Close()
		stop := context.AfterFunc(probeCtx, func() { conn.Close() })
		defer stop()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		question := new(dns.Msg)
		question.SetQuestion(dns.Fqdn(domain), dns.TypeA)
		payload, err := question.Pack()
		if err != nil {
			return
		}
		if _, err = conn.Write(payload); err != nil {
			return
		}
		response := make([]byte, 4096)
		size, err := conn.Read(response)
		if err != nil {
			return
		}
		report["udp"] = "response"
		answer := new(dns.Msg)
		if answer.Unpack(response[:size]) != nil || !answer.Response || answer.Id != question.Id || len(answer.Question) != 1 ||
			!strings.EqualFold(answer.Question[0].Name, question.Question[0].Name) || answer.Question[0].Qtype != dns.TypeA || answer.Question[0].Qclass != dns.ClassINET {
			return
		}
		report["outbound_dns_udp"] = "response"
		report["rcode"] = answer.Rcode
		report["answers"] = len(answer.Answer)
		report["truncated"] = answer.Truncated
		report["dns_ms"] = time.Since(started).Milliseconds()
	}()
	func() {
		probeCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
		defer cancel()
		started := time.Now()
		conn, err := dialer.DialContext(probeCtx, N.NetworkTCP, M.ParseSocksaddr(net.JoinHostPort(domain, "443")))
		if err != nil {
			return
		}
		defer conn.Close()
		stop := context.AfterFunc(probeCtx, func() { conn.Close() })
		defer stop()
		_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
		report["tcp"] = "dialed"
		report["tcp_ms"] = time.Since(started).Milliseconds()
		secure := tls.Client(conn, &tls.Config{ServerName: domain, MinVersion: tls.VersionTLS12})
		if secure.HandshakeContext(probeCtx) != nil {
			report["tls"] = "failed"
			return
		}
		report["tls"] = "verified"
		report["tls_ms"] = time.Since(started).Milliseconds()
	}()
	return report
}
