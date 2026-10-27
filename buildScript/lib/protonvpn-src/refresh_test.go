package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	proton "github.com/ProtonMail/go-proton-api"
)

// renewingServer 是「access token 過期、但 RefreshToken 還有效」的那種伺服器：
// 舊 token 一律 401，POST /auth/v4/refresh 換一張新的，新 token 才給清單。
// 這正是 2026-10-13 用戶在實機上撞到的情境（他看到的是「工作階段已被拒絕」＋被踢回登入頁）。
func renewingServer(t *testing.T, newToken, newRef string) (*httptest.Server, *int, *int) {
	t.Helper()
	var logicalHits, refreshHits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/auth/v4/refresh":
			refreshHits++
			if r.Method != http.MethodPost {
				t.Errorf("refresh method = %s, want POST", r.Method)
			}
			// 換新靠的是**內容**而不是標頭：UID 與 RefreshToken 都在 body 裡，
			// 標頭只有 appversion／apiversion。
			var body struct {
				UID          string
				RefreshToken string
				GrantType    string
				ResponseType string
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("decode refresh body: %v", err)
			}
			if body.UID != "uid-test" || body.RefreshToken != "refresh-test" {
				t.Errorf("refresh body = %+v, want uid-test/refresh-test", body)
			}
			if body.GrantType != "refresh_token" || body.ResponseType != "token" {
				t.Errorf("refresh body grant/response type = %q/%q, want refresh_token/token",
					body.GrantType, body.ResponseType)
			}
			// ⚠️ 欄位是**頂層**、不是包在 "Auth" 裡面：庫用 `var res struct{ Auth }`，
			// 而 Go 的 encoding/json 對匿名欄位做「提升」，所以它讀的是
			// {"UID":..,"AccessToken":..}（實測：包成 {"Auth":{..}} 時解析出來全空、
			// 換新看似成功但 token 沒換，下一次仍然 401）。
			_, _ = w.Write([]byte(`{"UID":"uid-test","AccessToken":"` + newToken +
				`","RefreshToken":"` + newRef + `"}`))
			return
		case logicalsV2Path, legacyLogicalsPath:
			logicalHits++
			if got := r.Header.Get("Authorization"); got != "Bearer "+newToken {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"Code":10013,"Error":"Invalid access token"}`))
				return
			}
			_, _ = w.Write([]byte(serverListFixture))
			return
		case clientConfigPath:
			// nodes 現在會順帶要一份預設埠；這條路不是本測試的重點，給一個最小可用的
			// 回包就好（真給錯也不該讓清單失敗，那是 openvpn_servers_test.go 的工作）。
			_, _ = w.Write([]byte(`{"Code":1000,"DefaultPorts":{"OpenVPN":{"UDP":[1194],"TCP":[443]}}}`))
			return
		}
		t.Errorf("unexpected path %q", r.URL.Path)
		w.WriteHeader(http.StatusNotFound)
	}))
	return srv, &logicalHits, &refreshHits
}

func readStoredCredential(t *testing.T, path string) storedCredential {
	t.Helper()
	payload, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read session: %v", err)
	}
	var cred storedCredential
	if err := json.Unmarshal(payload, &cred); err != nil {
		t.Fatalf("parse session: %v", err)
	}
	return cred
}

func TestNodesRenewsAnExpiredAccessTokenInsteadOfBouncingToSignIn(t *testing.T) {
	srv, logicalHits, refreshHits := renewingServer(t, "token-refreshed", "refresh-rotated")
	defer srv.Close()

	dir := t.TempDir()
	statePath := writeTestSession(t, dir)

	out, code := runNodesWith(t, srv.URL, statePath)
	if code != 0 || !out.OK {
		t.Fatalf("nodes did not recover from an expired access token: code=%d out=%+v", code, out)
	}
	if len(out.Servers) != 2 {
		t.Errorf("servers = %d, want the fixture's two usable nodes", len(out.Servers))
	}
	// 換新只該發生一次，而且是在第一次被拒之後。
	if *refreshHits != 1 {
		t.Errorf("refresh calls = %d, want exactly 1", *refreshHits)
	}
	if *logicalHits != 2 {
		t.Errorf("logicals calls = %d, want 2 (rejected, then retried with the new token)", *logicalHits)
	}

	// 輪換後的憑證必須落盤，否則下一輪啟動又會拿舊的去撞 401，等於沒修。
	cred := readStoredCredential(t, statePath)
	if cred.AccessToken != "token-refreshed" {
		t.Errorf("persisted access token = %q, want the rotated one", cred.AccessToken)
	}
	if cred.RefreshToken != "refresh-rotated" {
		t.Errorf("persisted refresh token = %q, want the rotated one", cred.RefreshToken)
	}
	// 登入時存的使用者識別不能被換新抹掉（refresh 的回包不含 UserID）。
	if cred.UserID != "user-test" {
		t.Errorf("persisted user id = %q, want the stored user-test to survive", cred.UserID)
	}
	if cred.SavedAt == "" {
		t.Errorf("savedAt is empty, want a fresh timestamp")
	}
}

func TestNodesStillReportsExpiryWhenTheRefreshIsRejectedToo(t *testing.T) {
	srv, _ := mockServer(t, `{"Code":10013,"Error":"Invalid access token"}`, http.StatusUnauthorized)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code == 0 || out.OK {
		t.Fatalf("nodes reported success when both tokens are rejected: %+v", out)
	}
	if out.Code != codeSessionExpired {
		t.Errorf("code = %q, want %q", out.Code, codeSessionExpired)
	}
}

func TestNodesSkipsRenewalWhenTheSessionCarriesNoRefreshToken(t *testing.T) {
	// 舊版狀態檔可能只有 access token；這種情況下不該白打一次 /auth/v4/refresh，
	// 也不能把它誤判成網路錯誤——它確實就是要重登。
	dir := t.TempDir()
	statePath := dir + "/session.json"
	payload, err := json.Marshal(storedCredential{UID: "uid-test", AccessToken: "token-test"})
	if err != nil {
		t.Fatalf("marshal session: %v", err)
	}
	if err := os.WriteFile(statePath, payload, 0o600); err != nil {
		t.Fatalf("write session: %v", err)
	}

	srv, hits := mockServer(t, `{"Code":10013,"Error":"Invalid access token"}`, http.StatusUnauthorized)
	defer srv.Close()

	out, _ := runNodesWith(t, srv.URL, statePath)
	if out.Code != codeSessionExpired {
		t.Errorf("code = %q, want %q", out.Code, codeSessionExpired)
	}
	if got := *hits(); got != 1 {
		t.Errorf("logicals calls = %d, want 1 (no renewal attempt without a refresh token)", got)
	}
}

func TestAuthRejectionDoesNotMisreadAFailingNetworkAsExpiry(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"401", proton.APIError{Status: http.StatusUnauthorized}, true},
		{"403", proton.APIError{Status: http.StatusForbidden}, true},
		{"refresh token invalid", errNoRefreshToken, true},
		{"rejected session", errSessionExpired, true},
		{"empty renewed token", errEmptyRefreshedToken, true},
		{"500", proton.APIError{Status: http.StatusInternalServerError}, false},
		{"timeout", errors.New("dial tcp: i/o timeout"), false},
	}
	for _, tc := range cases {
		if got := isAuthRejection(tc.err); got != tc.want {
			t.Errorf("%s: isAuthRejection = %v, want %v", tc.name, got, tc.want)
		}
	}
	// 庫實際丟進錯誤鏈的是 *APIError（resty 的 SetError 收指標），指標與值兩種都要認，
	// 否則「換新也被拒」會被誤判成網路錯誤、Code 欄位就空了（實測踩過一次）。
	if !isAuthRejection(&proton.APIError{Status: http.StatusForbidden}) {
		t.Errorf("pointer *APIError(403) must count as an auth rejection")
	}
}

// TestRefreshSessionTreatsAnEmptyTokenAsFailure 釘住那個會靜默壞事的形態：
// 200 但內容解析不出 token（例如 Proton 哪天把欄位包進一層 envelope）。
// 這種時候必須報失敗、不改狀態檔，否則下一次清單仍然 401，而日誌看起來「換新成功」。
func TestRefreshSessionTreatsAnEmptyTokenAsFailure(t *testing.T) {
	dir := t.TempDir()
	statePath := writeTestSession(t, dir)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"Auth":{"UID":"uid-test","AccessToken":"token-refreshed"}}`))
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if _, err := refreshSession(ctx, srv.URL, statePath, readStoredCredential(t, statePath)); !errors.Is(err, errEmptyRefreshedToken) {
		t.Fatalf("err = %v, want %v", err, errEmptyRefreshedToken)
	}
	if cred := readStoredCredential(t, statePath); cred.AccessToken != "token-test" {
		t.Errorf("session was rewritten after an empty renewal: %+v", cred)
	}
}

func TestRefreshSessionWritesNothingWhenProtonRejectsIt(t *testing.T) {
	// 換新失敗時狀態檔必須保持原樣：寫入「半套」會讓下次啟動連舊 token 都拿不到。
	dir := t.TempDir()
	statePath := writeTestSession(t, dir)

	srv, _ := mockServer(t, `{}`, http.StatusUnauthorized)
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if _, err := refreshSession(ctx, srv.URL, statePath, readStoredCredential(t, statePath)); err == nil {
		t.Fatalf("refresh against a rejecting server returned no error")
	}
	if cred := readStoredCredential(t, statePath); cred.AccessToken != "token-test" {
		t.Errorf("session was rewritten after a failed refresh: %+v", cred)
	}
}

// TestRefreshCommandRenewsInPlace 釘住主動續期：換到的一組要落回同一個狀態檔，
// 並回報 renewed=true（App 靠它分辨「真的換了」與「本來就沒到期」）。
func TestRefreshCommandRenewsInPlace(t *testing.T) {
	srv, _, refreshHits := renewingServer(t, "token-new", "refresh-new")
	defer srv.Close()

	statePath := writeTestSession(t, t.TempDir())
	var stdout, stderr bytes.Buffer
	code := runRefresh([]string{"--state", statePath, "--api-url", srv.URL}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("runRefresh exit=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	var out refreshOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse %q: %v", stdout.String(), err)
	}
	if !out.OK || !out.Renewed {
		t.Fatalf("out = %+v, want ok and renewed", out)
	}
	if *refreshHits != 1 {
		t.Errorf("refresh endpoint hit %d times, want exactly 1", *refreshHits)
	}
	cred := readStoredCredential(t, statePath)
	if cred.AccessToken != "token-new" || cred.RefreshToken != "refresh-new" {
		t.Errorf("session file = %+v, want the rotated pair written back", cred)
	}
}

// TestRefreshCommandFailsLoudlyWithoutARefreshToken：舊狀態檔沒有 RefreshToken 時
// 必須報 session_expired，讓 App 去重登；報成功會讓用戶以為續好了。
func TestRefreshCommandFailsLoudlyWithoutARefreshToken(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "session.json")
	payload, err := json.Marshal(storedCredential{UID: "uid-test", AccessToken: "token-test"})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		t.Fatalf("write session: %v", err)
	}

	var stdout, stderr bytes.Buffer
	code := runRefresh([]string{"--state", path, "--api-url", "http://127.0.0.1:1"}, &stdout, &stderr)
	if code == 0 {
		t.Fatalf("exit=%d with no refresh token, want failure", code)
	}
	var out refreshOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse %q: %v", stdout.String(), err)
	}
	if out.OK || out.Code != codeSessionExpired {
		t.Errorf("out = %+v, want code %s", out, codeSessionExpired)
	}
}
