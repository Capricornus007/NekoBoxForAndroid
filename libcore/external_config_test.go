package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestPrepareExternalSingBoxConfigMaterializesLegacyGeoRuleSet(t *testing.T) {
	oldExternalAssetsPath := externalAssetsPath
	externalAssetsPath = t.TempDir()
	t.Cleanup(func() { externalAssetsPath = oldExternalAssetsPath })

	const config = `{
		"outbounds": [{"type": "direct", "tag": "direct"}],
		"route": {
			"rule_set": [{
				"type": "local",
				"tag": "geosite:cn",
				"format": "binary",
				"path": "geosite:cn"
			}],
			"final": "direct"
		}
	}`
	prepared, err := PrepareExternalSingBoxConfig(config, nil)
	if err != nil {
		t.Fatal(err)
	}

	var decoded struct {
		Route struct {
			RuleSet []struct {
				Path string `json:"path"`
			} `json:"rule_set"`
		} `json:"route"`
	}
	if err := json.Unmarshal([]byte(prepared), &decoded); err != nil {
		t.Fatal(err)
	}
	if len(decoded.Route.RuleSet) != 1 {
		t.Fatalf("rule-set count = %d, want 1", len(decoded.Route.RuleSet))
	}
	want := filepath.Join(externalAssetsPath, "srs", "geosite_cn.srs")
	if got := decoded.Route.RuleSet[0].Path; got != want {
		t.Fatalf("prepared rule-set path = %q, want %q", got, want)
	}
	if _, err := os.Stat(want); err != nil {
		t.Fatalf("prepared SRS cache was not written: %v", err)
	}
}
