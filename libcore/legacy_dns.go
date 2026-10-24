package libcore

import (
	"bytes"
	"context"
	stdjson "encoding/json"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	singjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

// rcode:// is a DNS transport in old imports. Retaining a local transport also
// preserves explicit resolver references and never substitutes network DNS.
type legacyRcodeOptions struct {
	Rcode option.DNSRCode `json:"rcode"`
}
type legacyRcodeTransport struct {
	dns.TransportAdapter
	rcode int
}

func newLegacyRcodeTransport(_ context.Context, _ log.ContextLogger, tag string, options legacyRcodeOptions) (adapter.DNSTransport, error) {
	return &legacyRcodeTransport{dns.NewTransportAdapter("neko-rcode", tag, nil), int(options.Rcode)}, nil
}
func (t *legacyRcodeTransport) Start(adapter.StartStage, *adapter.Scope) error { return nil }
func (t *legacyRcodeTransport) Reset()                                         {}
func (t *legacyRcodeTransport) Exchange(_ context.Context, request *mDNS.Msg) (*mDNS.Msg, error) {
	return new(mDNS.Msg).SetRcode(request, t.rcode), nil
}
func (t *legacyRcodeTransport) ExchangeAsync(ctx context.Context, request *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	response, err := t.Exchange(ctx, request)
	callback(response, err)
}

// Decode every legacy field before conversion so bad types and unknown options
// cannot disappear just because their transport does not use them.
type legacyDNSServerInput struct {
	Type                 string                `json:"type"`
	Tag                  string                `json:"tag"`
	Address              string                `json:"address"`
	AddressResolver      string                `json:"address_resolver"`
	AddressStrategy      option.DomainStrategy `json:"address_strategy"`
	AddressFallbackDelay badoption.Duration    `json:"address_fallback_delay"`
	Strategy             option.DomainStrategy `json:"strategy"`
	Detour               string                `json:"detour"`
	ClientSubnet         *badoption.Prefixable `json:"client_subnet"`
}
type legacyFakeIPInput struct {
	Enabled    bool              `json:"enabled"`
	Inet4Range *badoption.Prefix `json:"inet4_range"`
	Inet6Range *badoption.Prefix `json:"inet6_range"`
}

func validateLegacyDNS(value any, output any) error {
	data, err := singjson.Marshal(value)
	if err != nil {
		return err
	}
	decoder := stdjson.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	return decoder.Decode(output)
}

func migrateLegacyDNSServers(dnsConfig map[string]any) error {
	servers, _ := dnsConfig["servers"].([]any)
	var fakeOptions legacyFakeIPInput
	fake, hasFake := dnsConfig["fakeip"]
	if hasFake && fake != nil {
		if err := validateLegacyDNS(fake, &fakeOptions); err != nil {
			return fmt.Errorf("invalid legacy DNS fakeip options")
		}
		if fakeOptions.Inet4Range != nil && !netip.Prefix(*fakeOptions.Inet4Range).Addr().Is4() {
			return fmt.Errorf("invalid fakeip IPv4 range")
		}
		if fakeOptions.Inet6Range != nil && !netip.Prefix(*fakeOptions.Inet6Range).Addr().Is6() {
			return fmt.Errorf("invalid fakeip IPv6 range")
		}
	}
	// The old global ranges also applied to typed fakeip transports.
	for _, item := range servers {
		server, ok := item.(map[string]any)
		if !ok || server["type"] != "fakeip" || !fakeOptions.Enabled {
			continue
		}
		if ranges, ok := fake.(map[string]any); ok {
			for _, key := range []string{"inet4_range", "inet6_range"} {
				if _, exists := server[key]; !exists && ranges[key] != nil {
					server[key] = ranges[key]
				}
			}
		}
	}
	if hasFake {
		delete(dnsConfig, "fakeip")
	}
	defaults := make(map[string]map[string]any)
	bootstrapTag := "neko-legacy-bootstrap"
	used := make(map[string]bool)
	for i, item := range servers {
		if server, ok := item.(map[string]any); ok {
			tag, _ := server["tag"].(string)
			if tag == "" {
				tag = strconv.Itoa(i)
			}
			used[tag] = true
		}
	}
	for used[bootstrapTag] {
		bootstrapTag += "-local"
	}
	needsBootstrap := false
	for i, item := range servers {
		server, ok := item.(map[string]any)
		if !ok {
			continue
		}
		typeName, validType := server["type"].(string)
		if value, exists := server["type"]; exists && value != nil && !validType {
			return fmt.Errorf("DNS server[%d]: invalid type", i)
		}
		if typeName != "" && typeName != "legacy" {
			continue
		}
		address, ok := server["address"].(string)
		if !ok {
			continue
		}
		var input legacyDNSServerInput
		if err := validateLegacyDNS(server, &input); err != nil {
			return fmt.Errorf("legacy DNS server[%d]: invalid options", i)
		}

		tag, _ := server["tag"].(string)
		if tag == "" {
			tag = strconv.Itoa(i)
		}
		converted := map[string]any{"tag": tag}
		legacyDefaults := make(map[string]any)
		for _, key := range []string{"strategy", "client_subnet"} {
			if value, exists := server[key]; exists {
				legacyDefaults[key] = value
			}
		}
		defaults[tag] = legacyDefaults
		for _, key := range []string{"detour", "address_fallback_delay"} {
			if value, exists := server[key]; exists {
				field := key
				if field == "address_fallback_delay" {
					field = "fallback_delay"
				}
				converted[field] = value
			}
		}
		scheme, target := "udp", address
		var parsed *url.URL
		if strings.Contains(address, "://") {
			var err error
			parsed, err = url.Parse(address)
			if err != nil || parsed.User != nil || parsed.Fragment != "" {
				return fmt.Errorf("legacy DNS server[%d]: invalid address", i)
			}
			scheme, target = parsed.Scheme, parsed.Host
		} else if address == "local" || address == "localhost" || address == "fakeip" {
			scheme = address
			if scheme == "localhost" {
				scheme = "local"
			}
		}
		converted["type"] = scheme
		switch scheme {
		case "local":
		case "fakeip":
			if ranges, ok := fake.(map[string]any); ok && fakeOptions.Enabled {
				for _, key := range []string{"inet4_range", "inet6_range"} {
					if ranges[key] != nil {
						converted[key] = ranges[key]
					}
				}
			}
		case "rcode":
			codes := map[string]string{"success": "NOERROR", "format_error": "FORMERR", "server_failure": "SERVFAIL", "name_error": "NXDOMAIN", "not_implemented": "NOTIMP", "refused": "REFUSED"}
			code, exists := codes[target]
			if !exists {
				return fmt.Errorf("legacy DNS server[%d]: unknown rcode", i)
			}
			converted = map[string]any{"type": "neko-rcode", "tag": tag, "rcode": code}
		case "dhcp":
			if target != "auto" && target != "" {
				converted["interface"] = target
			}
		case "udp", "tcp", "tls", "quic", "https", "h3":
			// ParseSocksaddr accepts a uint16 port; reject overflow before narrowing.
			if _, port, err := net.SplitHostPort(target); err == nil {
				value, err := strconv.ParseUint(port, 10, 16)
				if err != nil || value == 0 {
					return fmt.Errorf("legacy DNS server[%d]: invalid port", i)
				}
			} else if strings.Contains(target, ":") {
				if _, err := netip.ParseAddr(strings.Trim(target, "[]")); err != nil {
					return fmt.Errorf("legacy DNS server[%d]: invalid endpoint", i)
				}
			}
			endpoint := M.ParseSocksaddr(target)
			if !endpoint.IsValid() {
				return fmt.Errorf("legacy DNS server[%d]: invalid endpoint", i)
			}
			converted["server"] = endpoint.AddrString()
			if endpoint.Port != 0 {
				converted["server_port"] = endpoint.Port
			}
			if scheme == "https" || scheme == "h3" {
				if parsed == nil {
					return fmt.Errorf("invalid legacy HTTPS DNS address")
				}
				path := parsed.EscapedPath()
				if path == "" {
					path = "/dns-query"
				}
				if parsed.RawQuery != "" {
					path += "?" + parsed.RawQuery
				}
				converted["path"] = path
			}
			resolver, _ := server["address_resolver"].(string)
			if resolver == "" && endpoint.IsDomain() {
				needsBootstrap, resolver = true, bootstrapTag
			}
			if resolver != "" {
				options := map[string]any{"server": resolver}
				if strategy, exists := server["address_strategy"]; exists {
					options["strategy"] = strategy
				}
				converted["domain_resolver"] = options
			}
		default:
			return fmt.Errorf("legacy DNS server[%d]: unsupported scheme", i)
		}
		servers[i] = converted
	}
	if len(defaults) == 0 {
		return nil
	}
	if needsBootstrap {
		servers = append(servers, map[string]any{"type": "local", "tag": bootstrapTag})
	}
	dnsConfig["servers"] = servers
	if _, exists := dnsConfig["fakeip"]; exists {
		delete(dnsConfig, "fakeip")
	}
	var apply func([]any)
	apply = func(rules []any) {
		for _, item := range rules {
			rule, ok := item.(map[string]any)
			if !ok {
				continue
			}
			tag, _ := rule["server"].(string)
			for key, value := range defaults[tag] {
				if _, exists := rule[key]; !exists {
					rule[key] = value
				}
			}
			if children, ok := rule["rules"].([]any); ok {
				apply(children)
			}
		}
	}
	rules, _ := dnsConfig["rules"].([]any)
	apply(rules)
	final, _ := dnsConfig["final"].(string)
	if final == "" && len(servers) > 0 {
		if first, ok := servers[0].(map[string]any); ok {
			final, _ = first["tag"].(string)
		}
	}
	if len(defaults[final]) > 0 {
		fallback := map[string]any{"server": final}
		for key, value := range defaults[final] {
			fallback[key] = value
		}
		rules = append(rules, fallback)
	}
	if rules != nil {
		dnsConfig["rules"] = rules
	}
	return nil
}
