package libcore

import (
	"encoding/json"
	"fmt"
	"libcore/device"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/service"
)

// Construct and close without Start: no TUN, listener or health probes.
func CheckConfig(config string, localTransport LocalDNSTransport) (err error) {
	defer device.DeferPanicToError("CheckConfig", func(e error) { err = e })
	instance, err := NewSingBoxInstance(config, localTransport)
	if err != nil {
		return err
	}
	defer instance.Close()
	dependencies := make(map[string][]string)
	for _, outbound := range instance.Outbound().Outbounds() {
		dependencies[outbound.Tag()] = outbound.Dependencies()
	}
	if manager := service.FromContext[adapter.EndpointManager](instance.ctx); manager != nil {
		for _, endpoint := range manager.Endpoints() {
			dependencies[endpoint.Tag()] = endpoint.Dependencies()
		}
	}
	visited := make(map[string]uint8)
	var visit func(string) error
	visit = func(tag string) error {
		links, exists := dependencies[tag]
		if !exists {
			return fmt.Errorf("outbound not found: %s", tag)
		}
		if visited[tag] == 1 {
			return fmt.Errorf("circular outbound dependency: %s", tag)
		}
		if visited[tag] == 2 {
			return nil
		}
		visited[tag] = 1
		for _, next := range links {
			if err := visit(next); err != nil {
				return err
			}
		}
		visited[tag] = 2
		return nil
	}
	for tag := range dependencies {
		if err := visit(tag); err != nil {
			return err
		}
	}

	var routing struct {
		Route struct {
			Final string
			Rules []json.RawMessage
		}
	}
	if err := json.Unmarshal([]byte(config), &routing); err != nil {
		return err
	}
	if routing.Route.Final != "" {
		if err := visit(routing.Route.Final); err != nil {
			return err
		}
	}
	var checkRules func([]json.RawMessage) error
	checkRules = func(rules []json.RawMessage) error {
		for _, raw := range rules {
			var rule struct {
				Action, Outbound string
				Rules            []json.RawMessage
			}
			if err := json.Unmarshal(raw, &rule); err != nil {
				return err
			}
			if (rule.Action == "" || rule.Action == "route") && rule.Outbound != "" {
				if err := visit(rule.Outbound); err != nil {
					return err
				}
			}
			if err := checkRules(rule.Rules); err != nil {
				return err
			}
		}
		return nil
	}
	if err := checkRules(routing.Route.Rules); err != nil {
		return err
	}
	return nil
}

func (b *BoxInstance) RuntimeDiagnostics(queryJSON string) (string, error) {
	query := historyQuery{Limit: 100}
	if len(queryJSON) > 4096 {
		return "", fmt.Errorf("diagnostic query is too large")
	}
	if queryJSON != "" {
		if err := json.Unmarshal([]byte(queryJSON), &query); err != nil {
			return "", err
		}
	}
	b.access.Lock()
	defer b.access.Unlock()
	groups := make([]map[string]any, 0)
	if b.state == 1 && b.Box != nil {
		for _, outbound := range b.Outbound().Outbounds() {
			if test, ok := outbound.(*group.URLTest); ok {
				groups = append(groups, test.SelectionDiagnostics())
				if len(groups) >= 128 {
					break
				}
			}
		}
	}
	targets := make([]map[string]string, 0)
	servers := make([]map[string]string, 0)
	if b.state == 1 && b.Box != nil {
		for _, outbound := range b.Outbound().Outbounds() {
			if _, isGroup := outbound.(adapter.OutboundGroup); isGroup || outbound.Type() == "block" {
				continue
			}
			if len(targets) >= 128 {
				break
			}
			targets = append(targets, map[string]string{"tag": outbound.Tag(), "type": outbound.Type()})
		}
		for _, server := range b.candidateChecker.servers {
			switch server.Type {
			case "udp", "tcp", "tls", "https", "quic", "h3":
				servers = append(servers, map[string]string{"tag": server.Tag, "type": server.Type})
			}
			if len(servers) >= 32 {
				break
			}
		}
	}
	report := map[string]any{"probe_targets": targets, "dns_servers": servers, "groups": groups, "debug_history": b.diagnostics != nil, "probes_paused": b.probesPaused}
	if b.diagnostics != nil {
		for key, value := range b.diagnostics.page(query) {
			report[key] = value
		}
	}
	wireguard := make([]map[string]any, 0)
	if b.state == 1 && b.Box != nil {
		collect := func(outbound adapter.Outbound) {
			if source, ok := outbound.(interface{ WireGuardDiagnostics() map[string]any }); ok && len(wireguard) < 64 {
				state := source.WireGuardDiagnostics()
				state["tag"] = outbound.Tag()
				wireguard = append(wireguard, state)
			}
		}
		for _, outbound := range b.Outbound().Outbounds() {
			collect(outbound)
		}
		if manager := service.FromContext[adapter.EndpointManager](b.ctx); manager != nil {
			for _, endpoint := range manager.Endpoints() {
				collect(endpoint)
			}
		}
	}
	report["wireguard"] = wireguard
	content, err := json.Marshal(report)
	if len(content) > 256*1024 {
		return "", fmt.Errorf("runtime diagnostics exceed 256 KiB")
	}
	return string(content), err
}
