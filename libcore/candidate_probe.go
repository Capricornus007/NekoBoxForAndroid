package libcore

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	sjson "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/service"
)

type candidateChecker struct {
	servers  []option.DNSServerOptions
	strategy C.DomainStrategy
	busy     atomic.Bool
}

func newCandidateChecker(options option.Options) *candidateChecker {
	checker := &candidateChecker{}
	if options.DNS != nil {
		checker.servers = options.DNS.Servers
		checker.strategy = C.DomainStrategy(options.DNS.Strategy)
	}
	return checker
}

func (c *candidateChecker) validate(dnsTag, link string) error {
	if _, err := validProbeURL(link); err != nil {
		return err
	}
	for _, source := range c.servers {
		if source.Tag != dnsTag {
			continue
		}
		switch source.Type {
		case C.DNSTypeUDP, C.DNSTypeTCP, C.DNSTypeTLS, C.DNSTypeHTTPS, C.DNSTypeQUIC, C.DNSTypeHTTP3:
			return nil
		default:
			return fmt.Errorf("DNS health check requires a remote network DNS transport")
		}
	}
	return fmt.Errorf("configured DNS health server is missing")
}

func probeErrorCode(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, context.Canceled) {
		return "cancelled"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	var nerr net.Error
	if errors.As(err, &nerr) && nerr.Timeout() {
		return "timeout"
	}
	var verification *tls.CertificateVerificationError
	var unknown x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	if errors.As(err, &verification) || errors.As(err, &unknown) || errors.As(err, &invalid) || errors.As(err, &hostname) {
		return "certificate"
	}
	return "connection"
}

func validProbeURL(link string) (*url.URL, error) {
	u, err := url.Parse(link)
	if err != nil || len(link) > 2048 || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		return nil, fmt.Errorf("a valid HTTPS URL is required")
	}
	if port := u.Port(); port != "" {
		n, err := strconv.Atoi(port)
		if err != nil || n < 1 || n > 65535 {
			return nil, fmt.Errorf("invalid HTTPS port")
		}
	}
	return u, nil
}

// Use an isolated copy of the actual typed DNS transport. Neither live DNS
// configuration nor the selected proxy are modified by a probe.
func (c *candidateChecker) CheckCandidate(ctx context.Context, tag, dnsTag, link string) (uint16, map[string]any, error) {
	report := map[string]any{"dns_server": dnsTag, "dns": map[string]any{"state": "not-tested"}, "https": map[string]any{"state": "not-tested"}}
	u, err := validProbeURL(link)
	if err != nil {
		return 0, report, err
	}
	manager := service.FromContext[adapter.OutboundManager](ctx)
	outbound, exists := manager.Outbound(tag)
	if !exists {
		return 0, report, fmt.Errorf("probe outbound unavailable")
	}
	// Probing a group would not compare a specific candidate, and can recurse
	// through its own health check. Android lists only concrete candidates.
	if _, isGroup := outbound.(adapter.OutboundGroup); isGroup {
		return 0, report, fmt.Errorf("choose a concrete candidate")
	}
	var source *option.DNSServerOptions
	for index := range c.servers {
		if c.servers[index].Tag == dnsTag {
			source = &c.servers[index]
			break
		}
	}
	if source == nil {
		return 0, report, fmt.Errorf("configured DNS server unavailable")
	}
	switch source.Type {
	case C.DNSTypeUDP, C.DNSTypeTCP, C.DNSTypeTLS, C.DNSTypeHTTPS, C.DNSTypeQUIC, C.DNSTypeHTTP3:
	default:
		report["dns"] = map[string]any{"state": "unsupported", "type": source.Type}
		return 0, report, fmt.Errorf("DNS transport cannot be tested through a candidate")
	}
	encoded, err := sjson.MarshalContext(ctx, source)
	if err != nil {
		return 0, report, fmt.Errorf("copy DNS transport")
	}
	var raw map[string]any
	if err = json.Unmarshal(encoded, &raw); err != nil {
		return 0, report, fmt.Errorf("copy DNS options")
	}
	raw["detour"] = tag
	// Empty direct outbounds are deliberately rejected as detours by sing-box.
	// The ordinary protected direct dialer is the same path for this one tag.
	if tag == "direct" && outbound.Type() == "direct" {
		delete(raw, "detour")
	}
	raw["tag"] = "candidate-probe-dns"
	encoded, _ = json.Marshal(raw)
	var cloned option.DNSServerOptions
	if err = sjson.UnmarshalContext(ctx, encoded, &cloned); err != nil {
		return 0, report, fmt.Errorf("decode isolated DNS transport")
	}
	dnsReport := map[string]any{"state": "not-tested", "type": source.Type}
	report["dns"] = dnsReport
	var addressOptions struct {
		option.DialerOptions
		Server string
	}
	_ = json.Unmarshal(encoded, &addressOptions)
	bootstrap := map[string]any{"state": "numeric-address"}
	dnsReport["bootstrap"] = bootstrap
	if _, err := netip.ParseAddr(addressOptions.Server); err != nil {
		started := time.Now()
		queryOptions, err := dialer.NewDNSQueryOptions(ctx, addressOptions.DomainResolver, true)
		if err == nil && queryOptions.Transport != nil && queryOptions.Transport.Tag() == dnsTag {
			err = fmt.Errorf("recursive DNS bootstrap")
		}
		if err == nil {
			bootstrapCtx, stop := context.WithTimeout(ctx, 3*time.Second)
			var addresses []netip.Addr
			addresses, err = service.FromContext[adapter.DNSRouter](ctx).Lookup(bootstrapCtx, addressOptions.Server, queryOptions)
			stop()
			if err == nil && len(addresses) == 0 {
				err = fmt.Errorf("no bootstrap addresses")
			}
		}
		bootstrap["ms"] = time.Since(started).Milliseconds()
		bootstrap["state"] = "resolved"
		if err != nil {
			bootstrap["state"], bootstrap["error"] = "failed", probeErrorCode(err)
			dnsReport["state"] = "failed"
			return 0, report, fmt.Errorf("DNS bootstrap: %w", safeProbeError(err))
		}
	}
	logger := log.NewNOPFactory().NewLogger("candidate-probe")
	transport, err := service.FromContext[adapter.DNSTransportRegistry](ctx).CreateDNSTransport(ctx, logger, "candidate-probe-dns", cloned.Type, cloned.Options)
	if err != nil {
		dnsReport["state"] = "failed"
		return 0, report, fmt.Errorf("initialize DNS probe")
	}
	scope := adapter.NewScope(ctx, logger)
	defer scope.Close()
	for _, stage := range adapter.ListStartStages {
		if err = scope.Start("candidate DNS", transport, stage); err != nil {
			return 0, report, fmt.Errorf("start DNS probe")
		}
	}
	var addresses []netip.Addr
	var queryErr error
	for _, kind := range []uint16{mDNS.TypeA, mDNS.TypeAAAA} {
		queryCtx, stop := context.WithTimeout(ctx, 4*time.Second)
		started := time.Now()
		request := new(mDNS.Msg).SetQuestion(mDNS.Fqdn(u.Hostname()), kind)
		response, err := transport.Exchange(queryCtx, request)
		stop()
		result := map[string]any{"state": "failed", "ms": time.Since(started).Milliseconds()}
		dnsReport[strings.ToLower(mDNS.TypeToString[kind])] = result
		if err != nil {
			result["error"] = probeErrorCode(err)
			queryErr = err
			continue
		}
		if response == nil || !response.Response || len(response.Question) != 1 ||
			!strings.EqualFold(response.Question[0].Name, request.Question[0].Name) || response.Question[0].Qtype != kind {
			result["error"] = "invalid-response"
			continue
		}
		result["rcode"], result["answers"] = response.Rcode, len(response.Answer)
		if response.Rcode != mDNS.RcodeSuccess || response.Truncated {
			result["error"] = "dns-response"
			continue
		}
		result["state"] = "answered"
		familyAddresses := 0
		for _, rr := range response.Answer {
			var address netip.Addr
			switch answer := rr.(type) {
			case *mDNS.A:
				address, _ = netip.AddrFromSlice(answer.A)
				address = address.Unmap()
			case *mDNS.AAAA:
				address, _ = netip.AddrFromSlice(answer.AAAA)
			}
			if !address.IsValid() || (kind == mDNS.TypeA && !address.Is4()) || (kind == mDNS.TypeAAAA && !address.Is6()) {
				continue
			}
			familyAddresses++
			if c.strategy == C.DomainStrategyIPv4Only && address.Is6() || c.strategy == C.DomainStrategyIPv6Only && address.Is4() {
				continue
			}
			addresses = append(addresses, address)
		}
		result["addresses"] = familyAddresses // Counts only; never export returned IPs.
	}
	if ctx.Err() != nil {
		return 0, report, ctx.Err()
	}
	if len(addresses) == 0 {
		dnsReport["state"], dnsReport["error"] = "failed", "no-addresses"
		if queryErr != nil {
			return 0, report, fmt.Errorf("DNS exchange: %w", safeProbeError(queryErr))
		}
		return 0, report, fmt.Errorf("DNS returned no usable addresses")
	}
	dnsReport["state"] = "answered"
	if c.strategy == C.DomainStrategyPreferIPv6 { // Keep each family's reply order.
		var ordered []netip.Addr
		for _, a := range addresses {
			if a.Is6() {
				ordered = append(ordered, a)
			}
		}
		for _, a := range addresses {
			if a.Is4() {
				ordered = append(ordered, a)
			}
		}
		addresses = ordered
	}
	httpsReport := map[string]any{"state": "failed", "tcp": "not-tested", "tls": "not-tested"}
	report["https"] = httpsReport
	started := time.Now()
	defer func() { httpsReport["ms"] = time.Since(started).Milliseconds() }()
	port := uint16(443)
	if u.Port() != "" {
		n, _ := strconv.Atoi(u.Port())
		port = uint16(n)
	}
	var conn net.Conn
	for _, ip := range addresses[:min(2, len(addresses))] {
		conn, err = outbound.DialContext(ctx, "tcp", M.SocksaddrFromNetIP(netip.AddrPortFrom(ip, port)))
		if err == nil {
			break
		}
	}
	if err != nil {
		httpsReport["tcp"], httpsReport["error"] = "failed", probeErrorCode(err)
		return 0, report, fmt.Errorf("HTTPS connection: %w", safeProbeError(err))
	}
	defer conn.Close()
	stopClose := context.AfterFunc(ctx, func() { conn.Close() })
	defer stopClose()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	httpsReport["tcp"] = "connected"
	secure := tls.Client(conn, &tls.Config{ServerName: u.Hostname(), MinVersion: tls.VersionTLS12,
		RootCAs: adapter.RootPoolFromContext(ctx), Time: ntp.TimeFuncFromContext(ctx), NextProtos: []string{"http/1.1"}})
	if err = secure.HandshakeContext(ctx); err != nil {
		httpsReport["tls"], httpsReport["error"] = "failed", probeErrorCode(err)
		return 0, report, fmt.Errorf("HTTPS TLS: %w", safeProbeError(err))
	}
	httpsReport["tls"] = "verified"
	request, _ := http.NewRequestWithContext(ctx, http.MethodHead, link, nil)
	request.Close = true
	if err = request.Write(secure); err != nil {
		httpsReport["error"] = probeErrorCode(err)
		return 0, report, fmt.Errorf("HTTPS request: %w", safeProbeError(err))
	}
	// Headers are enough; do not download a page or follow redirects.
	response, err := http.ReadResponse(bufio.NewReader(io.LimitReader(secure, 64*1024)), request)
	httpsReport["ms"] = time.Since(started).Milliseconds()
	if err != nil {
		httpsReport["error"] = probeErrorCode(err)
		return 0, report, fmt.Errorf("HTTPS response: %w", safeProbeError(err))
	}
	response.Body.Close()
	httpsReport["status"] = response.StatusCode
	if response.StatusCode < 200 || response.StatusCode >= 400 {
		httpsReport["error"] = "http-status"
		return 0, report, fmt.Errorf("HTTPS status %d", response.StatusCode)
	}
	httpsReport["state"] = "reachable"
	return uint16(min(65535, max(1, time.Since(started).Milliseconds()))), report, nil
}

// Error text deliberately excludes URL paths, headers and answers. Wrapping
// cancellation/deadlines retains the group's cancellation/failure distinction.
func safeProbeError(err error) error {
	if errors.Is(err, context.Canceled) {
		return context.Canceled
	}
	if probeErrorCode(err) == "timeout" {
		return context.DeadlineExceeded
	}
	return errors.New(probeErrorCode(err))
}

func (b *BoxInstance) ConfiguredProbe(tag, dnsTag, link string) (string, error) {
	if _, err := validProbeURL(link); err != nil {
		return "", err
	}
	if len(tag) > 1024 || len(dnsTag) > 1024 {
		return "", fmt.Errorf("invalid probe target")
	}
	b.access.Lock()
	if b.state != 1 || b.probesPaused || b.pauseManager.IsDevicePaused() || b.pauseManager.IsNetworkPaused() {
		b.access.Unlock()
		return "", fmt.Errorf("network checks are paused or core is unavailable")
	}
	if !b.candidateChecker.busy.CompareAndSwap(false, true) {
		b.access.Unlock()
		return "", fmt.Errorf("a comparison is already running")
	}
	b.probeWG.Add(1)
	if b.probePowerCtx == nil {
		b.probePowerCtx, b.probePowerCancel = context.WithCancel(b.ctx)
	}
	ctx, cancel := context.WithTimeout(b.probePowerCtx, 18*time.Second)
	b.access.Unlock()
	defer b.probeWG.Done()
	defer cancel()
	defer b.candidateChecker.busy.Store(false)
	_, report, err := b.candidateChecker.CheckCandidate(ctx, tag, dnsTag, link)
	if err != nil {
		report["error"] = err.Error()
	}
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	content, err := json.Marshal(report)
	return string(content), err
}

func (b *BoxInstance) ResetProbeHealth() {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return
	}
	for _, outbound := range b.Outbound().Outbounds() {
		if test, ok := outbound.(*group.URLTest); ok {
			test.ResetProbeHealth()
		}
	}
}
