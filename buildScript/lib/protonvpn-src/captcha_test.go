package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func runCaptchaBeginJSON(t *testing.T, in captchaBeginInput) (captchaBeginOutput, int) {
	t.Helper()
	payload, err := json.Marshal(in)
	if err != nil {
		t.Fatalf("marshal input: %v", err)
	}
	var stdout, stderr bytes.Buffer
	code := runCaptchaBegin(nil, bytes.NewReader(payload), &stdout)
	var out captchaBeginOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v (stderr %q)", stdout.String(), err, stderr.String())
	}
	return out, code
}

func runCaptchaSolveJSON(t *testing.T, in captchaSolveInput) (captchaSolveOutput, int) {
	t.Helper()
	payload, err := json.Marshal(in)
	if err != nil {
		t.Fatalf("marshal input: %v", err)
	}
	var stdout, stderr bytes.Buffer
	code := runCaptchaSolve([]string{"--timeout", "5"}, bytes.NewReader(payload), &stdout)
	var out captchaSolveOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v (stderr %q)", stdout.String(), err, stderr.String())
	}
	return out, code
}

// captcha-begin 是把「9001 的 Details」翻成「WebView 該載什麼」的唯一場所：協定字串都
// 留在 Go，Kotlin 不自己拼 URL，Proton 改格式時只有一處要動。
func TestCaptchaBeginBuildsBothPages(t *testing.T) {
	out, code := runCaptchaBeginJSON(t, captchaBeginInput{
		Token:   "HVSTART123",
		Methods: []string{"captcha"},
		APIURL:  "https://api.protonmail.ch",
		Dark:    true,
	})
	if code != 0 || !out.OK {
		t.Fatalf("captcha-begin failed: code=%d out=%+v", code, out)
	}
	if out.Token != "HVSTART123" {
		t.Errorf("token = %q", out.Token)
	}
	if !strings.HasPrefix(out.SolveURL, hvVerifyApp+"/?") || !strings.Contains(out.SolveURL, "embed=true") {
		t.Errorf("solveURL = %q, want the embedded verify app", out.SolveURL)
	}
	// 備選那條只要 API 網域可达，而且要的是原生橋模式（ForceWebMessaging=0）。
	if !strings.Contains(out.CaptchaURL, "https://api.protonmail.ch"+hvCaptchaPath) ||
		!strings.Contains(out.CaptchaURL, "ForceWebMessaging=0") ||
		!strings.Contains(out.CaptchaURL, "Dark=true") {
		t.Errorf("captchaURL = %q", out.CaptchaURL)
	}
	if strings.Contains(out.SolveURL, "HVSTART123") == false {
		t.Error("the challenge token must be carried in the URL for the page to know what to verify")
	}
}

// 沒有 Details token 時不是死路：Proton 常把同一個連結写在英文訊息裡，撿得到就還能解。
func TestCaptchaBeginFallsBackToTheMessageLink(t *testing.T) {
	out, code := runCaptchaBeginJSON(t, captchaBeginInput{
		Error: "For security reasons, please complete CAPTCHA. " +
			"https://verify.proton.me/?methods=captcha&token=HVSTART123",
	})
	if code != 0 || !out.OK {
		t.Fatalf("the message link should be usable: code=%d out=%+v", code, out)
	}
	if !strings.Contains(out.MessageURL, "token=HVSTART123") {
		t.Errorf("messageURL = %q", out.MessageURL)
	}
	if out.SolveURL != "" || out.CaptchaURL != "" {
		t.Errorf("without a challenge token no in-app page should be offered: %+v", out)
	}
}

func TestCaptchaBeginRejectsAnEmptyChallenge(t *testing.T) {
	out, code := runCaptchaBeginJSON(t, captchaBeginInput{})
	if code == 0 || out.OK {
		t.Fatalf("an empty challenge must not look usable: %+v", out)
	}
	if out.Reason != reasonCaptchaRequired {
		t.Errorf("reason = %q, want %q", out.Reason, reasonCaptchaRequired)
	}
}

func TestCaptchaSolveNormalisesThePageAnswer(t *testing.T) {
	// 驗證頁交回的已經是複合 token（inline JS 的 sendToken），原樣留著。
	composite, code := runCaptchaSolveJSON(t, captchaSolveInput{
		Token: "HV1", Response: "HV1:answer",
	})
	if code != 0 || !composite.OK {
		t.Fatalf("solve failed: code=%d out=%+v", code, composite)
	}
	if composite.Token != "HV1:answer" {
		t.Errorf("token = %q, want HV1:answer", composite.Token)
	}
	// 類型缺欄位時補成 captcha：Proton 的 x-pm-human-verification-token-type 不能空白。
	if composite.Type != hvMethodCaptcha {
		t.Errorf("type = %q, want %q", composite.Type, hvMethodCaptcha)
	}

	// 只有內層結果時補前綴。
	repaired, _ := runCaptchaSolveJSON(t, captchaSolveInput{Token: "HV1", Response: "answer"})
	if repaired.Token != "HV1:answer" {
		t.Errorf("repaired = %q, want HV1:answer", repaired.Token)
	}

	// email/sms 這類成果的前綴是聯絡方式本身，不能被改寫成起點 token 開頭。
	foreign, _ := runCaptchaSolveJSON(t, captchaSolveInput{
		Token: "HV1", Response: "someone@example.com:123456", Type: "email",
	})
	if foreign.Token != "someone@example.com:123456" || foreign.Type != "email" {
		t.Errorf("foreign answer rewritten: %+v", foreign)
	}
}

func TestCaptchaSolveReportsExpiredAndMismatch(t *testing.T) {
	expired, code := runCaptchaSolveJSON(t, captchaSolveInput{Token: "HV1", Expired: true})
	if code == 0 || expired.OK || expired.Reason != "captcha-expired" {
		t.Errorf("expired report = %+v code=%d", expired, code)
	}
	empty, code := runCaptchaSolveJSON(t, captchaSolveInput{Token: "HV1"})
	if code == 0 || empty.OK || empty.Reason != "bad-request" {
		t.Errorf("empty answer report = %+v code=%d", empty, code)
	}
}

// submit=true 是「在外部瀏覽器解」那條路的兌換端點（WebClients 的
// submitExternalCaptcha：POST core/v4/verification/captcha/<起點>，body CaptchaToken）。
// 端點與欄位名都由這個測試盯住，Proton 改路徑時這裡先紅。
func TestCaptchaSolveSubmitUsesTheDocumentedEndpoint(t *testing.T) {
	var path, method, body, appVersion string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, method = r.URL.Path, r.Method
		body = r.Header.Get("Content-Type")
		appVersion = r.Header.Get("x-pm-appversion")
		_ = json.NewEncoder(w).Encode(map[string]any{"Code": 1000})
	}))
	defer srv.Close()

	out, code := runCaptchaSolveJSON(t, captchaSolveInput{
		Token: "HV1", Response: "HV1:answer", Submit: true, APIURL: srv.URL,
	})
	if code != 0 || !out.OK {
		t.Fatalf("submit failed: code=%d out=%+v", code, out)
	}
	if want := hvSubmitPath + "HV1"; path != want {
		t.Errorf("path = %q, want %q", path, want)
	}
	if method != http.MethodPost {
		t.Errorf("method = %q, want POST", method)
	}
	if !strings.HasPrefix(body, "application/json") {
		t.Errorf("content-type = %q", body)
	}
	if appVersion != protonAppVersion {
		t.Errorf("x-pm-appversion = %q, want %q", appVersion, protonAppVersion)
	}
}

func TestCaptchaSolveSubmitReportsAProtonRejection(t *testing.T) {
	// 12087 是社群量到的「CAPTCHA 驗證失敗」碼；正式站還是那個 4xx + Code/Error 骨架。
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"Code": 12087, "Error": "CAPTCHA validation failed",
		})
	}))
	defer srv.Close()

	out, code := runCaptchaSolveJSON(t, captchaSolveInput{
		Token: "HV1", Response: "HV1:answer", Submit: true, APIURL: srv.URL,
	})
	if code == 0 || out.OK {
		t.Fatalf("a rejected answer must not report success: %+v", out)
	}
	if out.Reason != "captcha-submit-failed" {
		t.Errorf("reason = %q", out.Reason)
	}
	if !strings.Contains(out.Error, "CAPTCHA validation failed") {
		t.Errorf("error = %q, want Proton's own wording", out.Error)
	}
	// 兌換失敗時不能把成果交回給 login 重試。
	if out.Token != "" {
		t.Errorf("token = %q, want none after a failed submit", out.Token)
	}
}

func TestCaptchaSolveNeedsTheTokenForSubmit(t *testing.T) {
	out, code := runCaptchaSolveJSON(t, captchaSolveInput{Response: "answer", Submit: true})
	if code == 0 || out.OK || out.Reason != "captcha-submit-failed" {
		t.Errorf("submit without the challenge token = %+v code=%d", out, code)
	}
}

// 子命令要在 run() 裡有名字，否則 Kotlin 呼叫時只會拿到 unknown command。
func TestCaptchaCommandsAreDispatched(t *testing.T) {
	var stdout, stderr bytes.Buffer
	in := []byte(`{"token":"HV1","response":"HV1:x"}`)
	if code := run([]string{"captcha-solve"}, bytes.NewReader(in), &stdout, &stderr); code != 0 {
		t.Fatalf("captcha-solve via run: code=%d stderr=%q", code, stderr.String())
	}
	stdout.Reset()
	if code := run([]string{"captcha-begin"}, strings.NewReader(`{"token":"HV1"}`), &stdout, &stderr); code != 0 {
		t.Fatalf("captcha-begin via run: code=%d stderr=%q", code, stderr.String())
	}
	usage := &stdout
	usage.Reset()
	if code := run([]string{"help"}, nil, usage, &stderr); code != 0 {
		t.Fatalf("help: code=%d", code)
	}
	for _, want := range []string{"captcha-begin", "captcha-solve"} {
		if !strings.Contains(usage.String(), want) {
			t.Errorf("usage does not list %q: %s", want, usage.String())
		}
	}
}
