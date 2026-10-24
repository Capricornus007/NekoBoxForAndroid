package libcore

import (
	"fmt"
	"strings"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// Resolve the app's database aliases before constructing the core. The resulting
// inline rule-sets use the public sing-box format, including on newer cores.
func materializeGeoRuleSets(options *option.Options) error {
	if options.Route == nil {
		return nil
	}
	loaded := make(map[string][]option.HeadlessRule)
	for i := range options.Route.RuleSet {
		set := &options.Route.RuleSet[i]
		if set.Type != C.RuleSetTypeLocal {
			continue
		}
		path := set.LocalOptions.Path
		var load func(string) ([]option.HeadlessRule, error)
		var name string
		switch {
		case strings.HasPrefix(path, "geoip:"):
			load, name = loadGeoIPRules, strings.TrimPrefix(path, "geoip:")
		case strings.HasPrefix(path, "geosite:"):
			load, name = loadGeoSiteRules, strings.TrimPrefix(path, "geosite:")
		default:
			continue
		}
		rules, exists := loaded[path]
		if !exists {
			var err error
			rules, err = load(name)
			if err != nil {
				return fmt.Errorf("rule-set %s (%s): %w", strings.Join(set.Tag, ","), path, err)
			}
			loaded[path] = rules
		}
		*set = option.RuleSet{
			Type: C.RuleSetTypeInline, Tag: set.Tag,
			InlineOptions: option.PlainRuleSet{Rules: rules},
		}
	}
	return nil
}
