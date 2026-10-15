package libcore

import (
	"bytes"
	"context"
	"fmt"
	"io"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/json/badoption"
)

// Migrations affect only the runtime copy; imported and stored JSON stays intact.
func decodeConfigOptions(ctx context.Context, content []byte, options *option.Options) error {
	normalized, err := normalizeConfigJSON(ctx, content)
	if err != nil {
		return err
	}
	return options.UnmarshalJSONContext(ctx, normalized)
}

func normalizeConfigJSON(ctx context.Context, content []byte) ([]byte, error) {
	decoder := json.NewDecoderContext(ctx, bytes.NewReader(content))
	decoder.UseNumber()
	var root map[string]any
	if err := decoder.Decode(&root); err != nil {
		return nil, err
	}
	if root == nil {
		return nil, fmt.Errorf("configuration must be an object")
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return nil, fmt.Errorf("configuration must contain one JSON object")
	}
	inbounds, _ := root["inbounds"].([]any)
	used := make(map[string]bool)
	for _, item := range inbounds {
		if inbound, ok := item.(map[string]any); ok {
			if tag, ok := inbound["tag"].(string); ok {
				used[tag] = true
			}
		}
	}
	var actions []any
	for i, item := range inbounds {
		inbound, ok := item.(map[string]any)
		if !ok {
			continue
		}
		sniff, _ := inbound["sniff"].(bool)
		override, _ := inbound["sniff_override_destination"].(bool)
		strategy, _ := inbound["domain_strategy"].(string)
		unmapping, _ := inbound["udp_disable_domain_unmapping"].(bool)
		if sniff || strategy != "" || unmapping {
			tag, _ := inbound["tag"].(string)
			if tag == "" {
				for n := i; ; n++ {
					tag = fmt.Sprintf("neko-legacy-inbound-%d", n)
					if !used[tag] {
						break
					}
				}
				inbound["tag"], used[tag] = tag, true
			}

			if sniff {
				action := map[string]any{"inbound": []string{tag}, "action": "sniff", "override_destination": override}
				if timeout, exists := inbound["sniff_timeout"]; exists {
					action["timeout"] = timeout
				}
				actions = append(actions, action)
			}
			if strategy != "" {
				actions = append(actions, map[string]any{"inbound": []string{tag}, "action": "resolve", "strategy": strategy})
			}
			if unmapping {
				actions = append(actions, map[string]any{"inbound": []string{tag}, "action": "route-options", "udp_disable_domain_unmapping": true})
			}
		}
		for _, field := range []string{"sniff", "sniff_timeout", "sniff_override_destination", "domain_strategy", "udp_disable_domain_unmapping"} {
			// Invalid values must reach strict core validation, rather than disappear.
			value, exists := inbound[field]
			if !exists {
				continue
			}
			valid := false
			switch field {
			case "domain_strategy":
				_, valid = value.(string)
			case "sniff_timeout":
				encoded, err := json.Marshal(value)
				var timeout badoption.Duration
				valid = err == nil && json.Unmarshal(encoded, &timeout) == nil
			default:
				_, valid = value.(bool)
			}
			if !valid {
				return nil, fmt.Errorf("inbound[%d]: invalid %s", i, field)
			}
			delete(inbound, field)
		}
		if inbound["type"] == "tun" {
			if value, exists := inbound["endpoint_independent_nat"]; exists {
				enabled, ok := value.(bool)
				if !ok {
					return nil, fmt.Errorf("invalid endpoint_independent_nat")
				}
				behavior := "address_and_port_dependent"
				if enabled {
					behavior = "endpoint_independent"
				}
				for _, field := range []string{"udp_mapping", "udp_filtering"} {
					if _, exists := inbound[field]; !exists {
						inbound[field] = behavior
					}
				}
				delete(inbound, "endpoint_independent_nat")
			}
		}
	}
	if len(actions) > 0 {
		route, ok := root["route"].(map[string]any)
		if !ok {
			if value := root["route"]; value != nil {
				return nil, fmt.Errorf("route must be an object")
			}
			route = make(map[string]any)
			root["route"] = route
		}
		rules, ok := route["rules"].([]any)
		if !ok && route["rules"] != nil {
			return nil, fmt.Errorf("route.rules must be an array")
		}
		position := 0
		for position < len(rules) && isEarlyNetworkGuard(rules[position]) {
			position++
		}
		merged := make([]any, 0, len(rules)+len(actions))
		merged = append(merged, rules[:position]...)
		merged = append(merged, actions...)
		merged = append(merged, rules[position:]...)
		route["rules"] = merged
	}
	if dns, ok := root["dns"].(map[string]any); ok {
		if err := migrateLegacyDNSServers(dns); err != nil {
			return nil, err
		}
		rules, _ := dns["rules"].([]any)
		if needsLegacyDNSRules(rules) {
			preserveLegacyQueryTypes(rules)
		}
	}
	return json.Marshal(root)
}

func isEarlyNetworkGuard(item any) bool {
	rule, ok := item.(map[string]any)
	if !ok || rule["action"] != "reject" {
		return false
	}
	var hasUID func(map[string]any) bool
	hasUID = func(rule map[string]any) bool {
		if rule["user_id"] != nil || rule["package_name"] != nil {
			return true
		}
		children, _ := rule["rules"].([]any)
		for _, child := range children {
			if nested, ok := child.(map[string]any); ok && hasUID(nested) {
				return true
			}
		}
		return false
	}
	if hasUID(rule) {
		return true
	}
	// The app's virtual-DNS TLS guard is before sniffing and destination resolution.
	ports, _ := rule["port"].([]any)
	for _, port := range ports {
		if fmt.Sprint(port) == "853" && rule["ip_cidr"] != nil {
			return true
		}
	}
	return false
}

func needsLegacyDNSRules(rules []any) bool {
	for _, item := range rules {
		rule, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if strategy, _ := rule["strategy"].(string); strategy != "" {
			return true
		}
		if rule["match_response"] == nil && (rule["ip_cidr"] != nil || rule["ip_is_private"] == true || rule["ip_accept_any"] == true || rule["rule_set_ip_cidr_accept_empty"] == true) {
			return true
		}
		if children, ok := rule["rules"].([]any); ok && needsLegacyDNSRules(children) {
			return true
		}
	}
	return false
}

func preserveLegacyQueryTypes(rules []any) {
	for _, item := range rules {
		rule, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if value, exists := rule["query_type"]; exists {
			if rule["neko_legacy_query_type"] == nil {
				rule["neko_legacy_query_type"] = value
				delete(rule, "query_type")
			}
		}
		if children, ok := rule["rules"].([]any); ok {
			preserveLegacyQueryTypes(children)
		}
	}
}
