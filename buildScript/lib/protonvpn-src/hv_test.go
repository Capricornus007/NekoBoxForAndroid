package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

// 這些 fixture 一律照 Proton 正式端點 2026-10-08 量測與公開源碼得來的樣式寫，
// 不要改成 go-proton-api 自己 mock 的形狀：那个 mock 的錯誤訊息欄位、Code 語意跟
// 正式站不同，照它寫測試會綠得毫無意義（這個坑我們踩過兩次）。
const realCaptchaChallenge = `{"Code":9001,` +
	`"Error":"For security reasons, please complete CAPTCHA. https://verify.proton.me/?methods=captcha&token=HVSTART123",` +
	`"Details":{"HumanVerificationMethods":["captcha"],"HumanVerificationToken":"HVSTART123"}}`

func TestParseHVChallengeReadsTheRealErrorShape(t *testing.T) {
	got := parseHVChallenge([]byte(realCaptchaChallenge))
	if got == nil {
		t.Fatal("the documented 422/9001 body was not recognised as a challenge")
	}
	if got.Token != "HVSTART123" {
		t.Errorf("token = %q, want HVSTART123", got.Token)
	}
	if !reflect.DeepEqual(got.Methods, []string{"captcha"}) {
		t.Errorf("methods = %v, want [captcha]", got.Methods)
	}
}

func TestParseHVChallengeIgnoresEverythingElse(t *testing.T) {
	// 少了 Details、Code 不是 9001、或 Details 沒有 token：都不能當成挑戰，否則我們會
	// 把一個跟驗證碼無關的失敗拿去附標頭。
	cases := []string{
		`{"Code":8002,"Error":"Wrong credentials"}`,
		`{"Code":9001,"Error":"Human verification required"}`,
		`{"Code":9001,"Error":"x","Details":{"HumanVerificationMethods":["captcha"]}}`,
		`{"Code":404,"Error":"Path not found","Details":{}}`,
		`not json at all`,
		``,
	}
	for _, body := range cases {
		if got := parseHVChallenge([]byte(body)); got != nil {
			t.Errorf("parseHVChallenge(%q) = %+v, want nil", body, got)
		}
	}
}

func TestComposeCaptchaTokenKeepsThePageFormat(t *testing.T) {
	// 驗證頁自己就交回 `<起點>:<結果>`（inline JS 的 sendToken），這種情況原樣留著。
	if got := composeCaptchaToken(hvMethodCaptcha, "HVSTART", "HVSTART:answer"); got != "HVSTART:answer" {
		t.Errorf("composite passthrough = %q", got)
	}
	// 只拿到結果、前綴不見了（WebView 攔到的是內層 iframe 的訊息）才補。
	if got := composeCaptchaToken(hvMethodCaptcha, "HVSTART", "answer"); got != "HVSTART:answer" {
		t.Errorf("repaired composite = %q, want HVSTART:answer", got)
	}
	// 已經帶別的來源前綴就猜不動，老實不改寫。
	if got := composeCaptchaToken(hvMethodCaptcha, "HVSTART", "other:123456"); got != "other:123456" {
		t.Errorf("foreign prefix = %q, want it untouched", got)
	}
	// email/sms 的前綴是聯絡方式本身（WebClients helper.ts getFormattedCode），把起點
	// token 拼在這種答案前面會直接讓重試被拒。
	if got := composeCaptchaToken("email", "HVSTART", "a@b.c:123456"); got != "a@b.c:123456" {
		t.Errorf("email answer = %q, want it untouched", got)
	}
	if got := composeCaptchaToken("email", "HVSTART", "123456"); got != "123456" {
		t.Errorf("bare code for email = %q, want it untouched", got)
	}
	if got := composeCaptchaToken(hvMethodCaptcha, "HVSTART", "  "); got != "" {
		t.Errorf("empty answer = %q, want empty", got)
	}
}

func TestVerifyAppURLMatchesTheOfficialAndroidClient(t *testing.T) {
	// 參數集合與值取自 protoncore_android HV3DialogFragment.buildUrl()：
	// embed=true, token, methods(逗號串), theme(1 深 2 淺)。
	challenge := &hvChallenge{Token: "HV START/+", Methods: []string{"captcha", "email"}}
	got := verifyAppURL(challenge, true)
	for _, want := range []string{"https://verify.proton.me/?", "embed=true", "token=HV+START%2F%2B", "methods=captcha%2Cemail", "theme=1"} {
		if !strings.Contains(got, want) {
			t.Errorf("verifyAppURL(dark) = %q, missing %q", got, want)
		}
	}
	if light := verifyAppURL(challenge, false); !strings.Contains(light, "theme=2") {
		t.Errorf("verifyAppURL(light) = %q, want theme=2", light)
	}
	if empty := verifyAppURL(&hvChallenge{}, false); empty != "" {
		t.Errorf("verifyAppURL without a token = %q, want empty", empty)
	}
	// 方法清單缺欄位時至少要有 captcha，否則頁面會開成「無可驗證方法」。
	onlyCaptcha := verifyAppURL(&hvChallenge{Token: "T"}, false)
	if !strings.Contains(onlyCaptcha, "methods=captcha") {
		t.Errorf("methods fallback = %q", onlyCaptcha)
	}
}

func TestCaptchaPageURLAsksForTheNativeBridge(t *testing.T) {
	// 實測（2026-10-08，api.protonmail.ch/core/v4/captcha）：ForceWebMessaging=1 時
	// 頁面內變數是 true（只認 window.parent.postMessage），0 或省略時是 false，此时它
	// 才改用 AndroidInterface.receiveResponse。我们的 WebView 要的是後者。
	got := captchaPageURL("https://api.protonmail.ch/", &hvChallenge{Token: "HV1"}, true)
	for _, want := range []string{"https://api.protonmail.ch/core/v4/captcha?", "Token=HV1", "ForceWebMessaging=0", "Dark=true"} {
		if !strings.Contains(got, want) {
			t.Errorf("captchaPageURL = %q, missing %q", got, want)
		}
	}
	if light := captchaPageURL("", &hvChallenge{Token: "HV1"}, false); strings.Contains(light, "Dark=") {
		t.Errorf("light mode URL should not ask for Dark: %q", light)
	}
	if !strings.HasPrefix(captchaPageURL("", &hvChallenge{Token: "HV1"}, false), defaultProtonAPIURL) {
		t.Error("an empty API URL should fall back to the production host")
	}
}

func TestHVURLFromMessage(t *testing.T) {
	message := "For security reasons, please complete CAPTCHA. " +
		"https://verify.proton.me/?methods=captcha&token=HVSTART123"
	got := hvURLFromMessage(message)
	if got == "" || !strings.Contains(got, "token=HVSTART123") {
		t.Errorf("hvURLFromMessage = %q, want the verify.proton.me link", got)
	}
	// 不是 Proton 網域的連結、或沒有 token 參數，一律不當答案，免得被訊息inject別的站台。
	for _, msg := range []string{
		"complete CAPTCHA at https://evil.example/?token=1",
		"see https://verify.proton.me/",
		"no link here",
	} {
		if got := hvURLFromMessage(msg); got != "" {
			t.Errorf("hvURLFromMessage(%q) = %q, want empty", msg, got)
		}
	}
}

func TestHVTransportAttachesHeadersOnlyOnAuthRoutes(t *testing.T) {
	var seen [][2]string
	var seenOnOther string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		// 兩個 auth 端點都要收得到：Proton 可能擋在交 proof 那一步，也可能更早、
		// 擋在 /auth/v4/info（實測過兩種擋點都有人回報）。
		case strings.HasPrefix(r.URL.Path, "/auth/"):
			seen = append(seen, [2]string{
				r.Header.Get(hvTokenHeader), r.Header.Get(hvTypeHeader),
			})
		case r.URL.Path == "/vpn/logicals":
			seenOnOther = r.Header.Get(hvTokenHeader)
		}
		_, _ = w.Write([]byte(`{"Code":1000}`))
	}))
	defer srv.Close()

	var challenge *hvChallenge
	tr := &hvTransport{
		base:      http.DefaultTransport,
		attach:    &hvSolution{Token: "HV1:solved"},
		challenge: &challenge,
	}
	for _, path := range []string{"/auth/v4", "/auth/v4/info", "/vpn/logicals"} {
		req, err := http.NewRequest(http.MethodPost, srv.URL+path, nil)
		if err != nil {
			t.Fatalf("new request: %v", err)
		}
		res, err := tr.RoundTrip(req)
		if err != nil {
			t.Fatalf("round trip %s: %v", path, err)
		}
		res.Body.Close()
	}
	if len(seen) != 2 {
		t.Fatalf("auth requests seen = %d, want 2", len(seen))
	}
	for _, pair := range seen {
		if pair[0] != "HV1:solved" {
			t.Errorf("%s = %q, want the composite token", hvTokenHeader, pair[0])
		}
		// 類型的預設值：captcha-solve 沒標類型時就是 captcha（Proton 要的不是空白）。
		if pair[1] != hvMethodCaptcha {
			t.Errorf("%s = %q, want %q", hvTypeHeader, pair[1], hvMethodCaptcha)
		}
	}
	if seenOnOther != "" {
		t.Errorf("the CAPTCHA token leaked onto a non-auth route: %q", seenOnOther)
	}
}

func TestHVTransportCapturesTheChallengeAndLeaksNothingToResty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(realCaptchaChallenge))
	}))
	defer srv.Close()

	var challenge *hvChallenge
	tr := &hvTransport{base: http.DefaultTransport, challenge: &challenge}
	req, err := http.NewRequest(http.MethodPost, srv.URL+"/auth/v4", nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	res, err := tr.RoundTrip(req)
	if err != nil {
		t.Fatalf("round trip: %v", err)
	}
	defer res.Body.Close()

	if challenge == nil || challenge.Token != "HVSTART123" {
		t.Fatalf("challenge = %+v, want the token from Details", challenge)
	}
	// 攔截不能吃掉 body：呼叫端（go-proton-api）要能再把同一份 JSON 讀一次。
	var body map[string]any
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatalf("the body was not re-readable after capture: %v", err)
	}
	if code, ok := body["Code"].(float64); !ok || int(code) != hvAPIErrorCode {
		t.Errorf("re-read body = %v, want Code %d", body["Code"], hvAPIErrorCode)
	}
}

func TestNormalizeMethods(t *testing.T) {
	if got := normalizeMethods(nil); !reflect.DeepEqual(got, []string{hvMethodCaptcha}) {
		t.Errorf("empty list = %v, want [captcha]", got)
	}
	if got := normalizeMethods([]string{"CAPTCHA", "captcha", " email ", "payment", ""}); !reflect.DeepEqual(got, []string{"captcha", "email"}) {
		t.Errorf("normalizeMethods = %v, want [captcha email]", got)
	}
}
