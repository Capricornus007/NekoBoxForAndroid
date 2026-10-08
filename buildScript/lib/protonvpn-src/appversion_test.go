package main

import (
	"regexp"
	"strings"
	"testing"
)

// appVersionPattern 是 Proton 正式閘門接受的「<平台>-<產品>@<版本>」外形。
// 平台與產品的白名單不在這裡重現（那是伺服器端的清單，改了我們也看不到），
// 這裡只擋住「又寫成底線分隔版本／大寫／漏 @／版本不是點分數字」這類一定會吃
// 400/5002、400/2064 的寫法。身分段允許底線，因為官方 TV 端就是 android_tv-vpn。
var appVersionPattern = regexp.MustCompile(`^[a-z][a-z_-]*@[0-9]+(\.[0-9]+)+$`)

func TestAppVersionHeaderShape(t *testing.T) {
	if !appVersionPattern.MatchString(protonAppVersion) {
		t.Fatalf("protonAppVersion = %q 不是「<平台>-<產品>@<版本>」格式，登入會吃 400/5002 或 400/2064", protonAppVersion)
	}
	if strings.Contains(protonAppVersion, "_@") || strings.HasPrefix(protonAppVersion, "@") {
		t.Fatalf("protonAppVersion = %q 分隔符號寫壞", protonAppVersion)
	}
}

// TestAppVersionHeaderIsNotTheOldWrongFormat 盯的是踩過的坑：最早寫成 "NB4A/1.0.0"、
// 後來改成 "NB4A_1.0.0"，兩者都被正式閘門擋在 400/5002，而依赖裡的 mock server
// （go-proton-api/server/router.go 的 validateAppVersion）卻會放過底線寫法，
// 所以「照 mock 的判式寫測試」會一路綠、真機一路登入不進去。
func TestAppVersionHeaderIsNotTheOldWrongFormat(t *testing.T) {
	for _, bad := range []string{"NB4A/1.0.0", "NB4A_1.0.0", "android-vpn_5.20.57.0", "android-vpn", "android-vpn@"} {
		if appVersionPattern.MatchString(bad) {
			t.Errorf("%q 被當成合法格式，但 Proton 會擋", bad)
		}
	}
}
