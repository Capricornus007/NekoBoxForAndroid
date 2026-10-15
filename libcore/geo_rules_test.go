package libcore

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func installGeoFixtures(t *testing.T) {
	t.Helper()
	dir := t.TempDir()
	original := externalAssetsPath
	externalAssetsPath = dir
	t.Cleanup(func() { externalAssetsPath = original })
	var site bytes.Buffer
	if err := geosites.Write(&site, map[string][]geosites.Item{"ru": {{Type: geosites.RuleTypeDomainSuffix, Value: ".ru"}}}); err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string][]byte{geoipDat: geoipFixture(), geositeDat: site.Bytes()} {
		if err := os.WriteFile(filepath.Join(dir, name), value, 0600); err != nil {
			t.Fatal(err)
		}
	}
}

func TestGeoAliasesBecomePublicRuleSetsAndStillMatch(t *testing.T) {
	installGeoFixtures(t)
	ipHook, siteHook := nekoutils.GetGeoIPHeadlessRules, nekoutils.GetGeoSiteHeadlessRules
	nekoutils.GetGeoIPHeadlessRules, nekoutils.GetGeoSiteHeadlessRules = nil, nil
	t.Cleanup(func() { nekoutils.GetGeoIPHeadlessRules, nekoutils.GetGeoSiteHeadlessRules = ipHook, siteHook })
	config := `{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rule_set":[{"type":"local","format":"binary","tag":"country","path":"geoip:RU"},{"type":"local","format":"binary","tag":"sites","path":"geosite:ru"}]}}`
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	for _, test := range []struct {
		tag      string
		metadata adapter.InboundContext
		want     bool
	}{
		{"country", adapter.InboundContext{Destination: M.ParseSocksaddr("203.0.113.4:443")}, true},
		{"sites", adapter.InboundContext{Domain: "example.ru"}, true},
		{"sites", adapter.InboundContext{Domain: "example.test"}, false},
	} {
		rules, ok := instance.Router().RuleSet(test.tag)
		if !ok || rules.Match(&test.metadata) != test.want {
			t.Fatalf("%s: missing or changed geographic match", test.tag)
		}
	}
	options := option.Options{Route: &option.RouteOptions{RuleSet: []option.RuleSet{{Type: "local", Tag: "ordinary", Format: "binary", LocalOptions: option.LocalRuleSet{Path: "/tmp/custom.srs"}}}}}
	if err := materializeGeoRuleSets(&options); err != nil || options.Route.RuleSet[0].LocalOptions.Path != "/tmp/custom.srs" {
		t.Fatal("changed an ordinary local rule-set", err)
	}
	_, err = NewSingBoxInstance(strings.ReplaceAll(config, "geoip:RU", "geoip:missing"), nil)
	if err == nil || !strings.Contains(err.Error(), "rule-set country") {
		t.Fatalf("missing category must fail clearly: %v", err)
	}
}

func TestGeoSiteLoadClosesDatabase(t *testing.T) {
	installGeoFixtures(t)
	before, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Skip("requires /proc/self/fd")
	}
	for range 32 {
		if _, err := loadGeoSiteRules("ru"); err != nil {
			t.Fatal(err)
		}
	}
	after, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal(err)
	}
	if len(after) > len(before)+1 {
		t.Fatalf("geosite database descriptors accumulated: %d -> %d", len(before), len(after))
	}
}
