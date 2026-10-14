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
	if err := json.Unmarshal([]byte(config), &routing); err != nil { return err }
	if routing.Route.Final != "" {
		if err := visit(routing.Route.Final); err != nil { return err }
	}
	var checkRules func([]json.RawMessage) error
	checkRules = func(rules []json.RawMessage) error {
		for _, raw := range rules {
			var rule struct { Action, Outbound string; Rules []json.RawMessage }
			if err := json.Unmarshal(raw, &rule); err != nil { return err }
			if (rule.Action == "" || rule.Action == "route") && rule.Outbound != "" {
				if err := visit(rule.Outbound); err != nil { return err }
			}
			if err := checkRules(rule.Rules); err != nil { return err }
		}
		return nil
	}
	if err := checkRules(routing.Route.Rules); err != nil { return err }
	return nil
}

func (b *BoxInstance) RuntimeDiagnostics() (string, error) {
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
	content, err := json.Marshal(map[string]any{"groups": groups})
	if len(content) > 512*1024 {
		return "", fmt.Errorf("runtime diagnostics exceed 512 KiB")
	}
	return string(content), err
}
