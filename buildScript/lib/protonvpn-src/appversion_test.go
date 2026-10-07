package main

import (
	"regexp"
	"strings"
	"testing"
)

// Proton 的閘門（參考實作：go-proton-api/server/router.go 的 validateAppVersion）把
// x-pm-appversion 切成「<產品名>_<semver>」，第二段要能當 semver 解析。這裡只驗
// 主.副.修 加可选的 prerelease／build，跟 semver 的實際接受範圍一致。
var semverRE = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$`)

func validAppVersionHeader(v string) bool {
	parts := strings.Split(v, "_")
	if len(parts) != 2 || parts[0] == "" {
		return false
	}
	return semverRE.MatchString(parts[1])
}

// 這支測試盯的是「登入會不會一開始就被 400 擋掉」：常數寫成 NB4A/1.0.0 那種斜線格式時，
// Proton 連 SRP 都不讓你走。
func TestAppVersionHeaderMatchesProtonsFormatRule(t *testing.T) {
	if !validAppVersionHeader(protonAppVersion) {
		t.Fatalf("protonAppVersion = %q 不符合 Proton 的 <產品名>_<semver> 格式，實際登入會吃到 400/5003", protonAppVersion)
	}
	for _, bad := range []string{"NB4A/1.0.0", "NB4A", "NB4A_1.0", "NB4A_1.0.0_1", "_1.0.0", "NB4A_v1.0.0", "NB4A_1.0.0-", ""} {
		if validAppVersionHeader(bad) {
			t.Errorf("validAppVersionHeader(%q) 回 true，但這個格式 Proton 會擋", bad)
		}
	}
}
