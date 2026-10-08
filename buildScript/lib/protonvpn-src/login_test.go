package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"

	proton "github.com/ProtonMail/go-proton-api"
	srp "github.com/ProtonMail/go-srp"
)

// 正式閘門的規則是「<平台>-<產品>@<版本>」，2026-10-08 用公開端點 /auth/v4/info 逐個
// 字串量測得來（該端點在驗 token 之前就會先過版本閘門，所以不需要帳密）：
//
//	"NB4A_1.0.0"             -> 400/5002 Invalid app version
//	"NB4A@1.0.0"             -> 400/2064 Application platform and product must be separated by a dash
//	"android-nb4a@1.0.0"     -> 400/2064 Product `nb4a` is not valid
//	"NB4A-android@1.0.0"     -> 400/2064 Application name must be in lowercase
//	"android-vpn@1.0.0"      -> 422/5003 This version of the app is no longer supported
//	"android-vpn@5.20.57.0"  -> 401（過閘門，進到驗 token）
//
// 這裡刻意不沿用 go-proton-api 那套 mock 的 strings.Split(v,"_") 判式：它放過底線寫法、
// 正式閘門不放，照它寫測試就會一路綠而真機永遠登入不進去（我們已經踩過兩次）。
var appVersionRE = regexp.MustCompile(`^([^@]+)@([0-9][0-9.]*)$`)

var knownPlatforms = map[string]bool{
	"android": true, "android_tv": true, "ios": true, "ios_tv": true,
	"web": true, "linux": true, "macos": true, "windows": true, "macos_appstore": true,
}

var knownProducts = map[string]bool{
	"vpn": true, "account": true, "mail": true, "calendar": true, "pass": true,
}

// minSupportedVersion 是「版本被淘汰」的下界，實測 5.10.0 起可過、1.0.0 吃 422/5003。
var minSupportedVersion = []int{5, 10}

// appVersionGate 回空字串代表放行，否則回 Proton 實際會給的那句錯誤。
func appVersionGate(v string) string {
	if v == "" {
		return "Missing x-pm-appversion header"
	}
	name, version, ok := strings.Cut(v, "@")
	if !ok {
		return "Invalid app version"
	}
	if strings.ToLower(name) != name {
		return "Application name must be in lowercase, got " + name
	}
	platform, product, hasDash := strings.Cut(name, "-")
	if !hasDash {
		return "Application platform and product must be separated by a dash"
	}
	if !knownPlatforms[platform] {
		return "Platform `" + platform + "` is not valid (in `" + v + "')"
	}
	if !knownProducts[product] {
		return "Product `" + product + "` is not valid (in `" + v + "')"
	}
	if !appVersionRE.MatchString(v) {
		return "Invalid app version"
	}
	got := strings.Split(version, ".")
	if len(got) < len(minSupportedVersion) {
		return "This version of the app is no longer supported"
	}
	for i, floor := range minSupportedVersion {
		n, err := strconv.Atoi(got[i])
		if err != nil || n < floor {
			return "This version of the app is no longer supported"
		}
		if n > floor {
			break
		}
	}
	return ""
}

// TestAppVersionHeaderMatchesProtonsFormatRule 盯的是「登入會不會一開始就被擋」：
// 常數寫成 NB4A/1.0.0 或 NB4A_1.0.0 那種格式時，Proton 連 SRP 都不讓你走。
func TestAppVersionHeaderMatchesProtonsFormatRule(t *testing.T) {
	if reason := appVersionGate(protonAppVersion); reason != "" {
		t.Fatalf("protonAppVersion = %q 會被 Proton 閘門擋掉：%s", protonAppVersion, reason)
	}
	bad := map[string]string{
		"NB4A/1.0.0":         "Invalid app version",
		"NB4A_1.0.0":         "Invalid app version",
		"android-vpn":        "Invalid app version",
		"android-vpn@":       "Invalid app version",
		"NB4A@1.0.0":         "Application name must be in lowercase",
		"NB4A-android@1.0.0": "Application name must be in lowercase",
		"android@1.0.0":      "Application platform and product must be separated by a dash",
		"android-nb4a@1.0.0": "Product `nb4a` is not valid",
		"solar-vpn@1.0.0":    "Platform `solar` is not valid",
		"android-vpn@1.0.0":  "This version of the app is no longer supported",
		"":                   "Missing x-pm-appversion header",
	}
	for v, want := range bad {
		got := appVersionGate(v)
		if got == "" {
			t.Errorf("appVersionGate(%q) 放行，但 Proton 會擋（期望含 %q）", v, want)
			continue
		}
		if !strings.HasPrefix(got, want) {
			t.Errorf("appVersionGate(%q) = %q，期望開頭是 %q", v, got, want)
		}
	}
	// 官方 TV 端是 android_tv-vpn（ProtonVPN/android-app Constants.kt:73-74），別把底線擋掉。
	for _, v := range []string{"android-vpn@5.20.57.0", "android_tv-vpn@5.20.57.0", "web-account@5.5.5.5"} {
		if got := appVersionGate(v); got != "" {
			t.Errorf("appVersionGate(%q) = %q，這個格式 Proton 是收的", v, got)
		}
	}
}

type mockAuthServer struct {
	server      *srp.Server
	salt        []byte
	twoFA       proton.TwoFAStatus
	uid         string
	accessToken string
	authCalls   int
	infoCalls   int
}

const (
	mockUsername = "nb4a-proton-test"
	mockPassword = "nb4a-proton-sidecar-login-test"
)

func newMockAuthServer(t *testing.T, twoFA proton.TwoFAStatus) *mockAuthServer {
	t.Helper()

	salt, err := srp.RandomBytes(10)
	if err != nil {
		t.Fatalf("random salt: %v", err)
	}
	auth, err := srp.NewAuthForVerifier([]byte(mockPassword), selftestSignedModulus, salt)
	if err != nil {
		t.Fatalf("new auth for verifier: %v", err)
	}
	verifier, err := auth.GenerateVerifier(2048)
	if err != nil {
		t.Fatalf("generate verifier: %v", err)
	}
	server, err := srp.NewServerFromSigned(selftestSignedModulus, verifier, 2048)
	if err != nil {
		t.Fatalf("new server: %v", err)
	}

	return &mockAuthServer{
		server:      server,
		salt:        salt,
		twoFA:       twoFA,
		uid:         "uid-mock-1",
		accessToken: "access-mock-1",
	}
}

func (m *mockAuthServer) handler(t *testing.T) http.Handler {
	t.Helper()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// mock 這裡照正式閘門的規則走（見 appVersionGate 的註解與量測記錄）。
		// 這一步要是缺席或写成舊的 "_" 判式，測試就會放過一個「登入必定無效」的常數。
		if reason := appVersionGate(r.Header.Get("x-pm-appversion")); reason != "" {
			code := 5002
			status := http.StatusBadRequest
			if strings.HasPrefix(reason, "This version") {
				code = 5003
				status = http.StatusUnprocessableEntity
			} else if strings.HasPrefix(reason, "Platform") || strings.HasPrefix(reason, "Product") ||
				strings.HasPrefix(reason, "Application name") || strings.HasPrefix(reason, "Application platform") {
				code = 2064
			}
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(status)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"Code":    code,
				"Message": reason,
			})
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/auth/v4/info":
			m.infoCalls++
			challenge, err := m.server.GenerateChallenge()
			if err != nil {
				t.Errorf("generate challenge: %v", err)
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			// The library decodes into an anonymous struct that embeds AuthInfo,
			// so encoding/json promotes those fields to the top level.
			_ = json.NewEncoder(w).Encode(map[string]any{
				"Version":         4,
				"Modulus":         selftestSignedModulus,
				"ServerEphemeral": base64.StdEncoding.EncodeToString(challenge),
				"Salt":            base64.StdEncoding.EncodeToString(m.salt),
				"SRPSession":      "srp-session-mock",
				"2FA":             map[string]any{"Enabled": m.twoFA, "FIDO2": map[string]any{}},
			})

		case "/auth/v4":
			m.authCalls++
			var req struct {
				Username        string
				ClientEphemeral string
				ClientProof     string
				SRPSession      string
			}
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				t.Errorf("decode auth req: %v", err)
			}
			if req.SRPSession != "srp-session-mock" {
				t.Errorf("SRPSession = %q, want the value handed out by /auth/v4/info", req.SRPSession)
			}
			ephemeral, err := base64.StdEncoding.DecodeString(req.ClientEphemeral)
			if err != nil {
				t.Errorf("client ephemeral is not base64: %v", err)
			}
			proof, err := base64.StdEncoding.DecodeString(req.ClientProof)
			if err != nil {
				t.Errorf("client proof is not base64: %v", err)
			}
			serverProof, err := m.server.VerifyProofs(ephemeral, proof)
			if err != nil {
				// Proton answers a bad SRP proof with HTTP 401 plus the API code;
				// a 200 here would make the library report ErrInvalidProof instead.
				w.WriteHeader(http.StatusUnauthorized)
				_ = json.NewEncoder(w).Encode(map[string]any{
					"Code":    int(proton.PasswordWrong),
					"Error":   "Wrong credentials",
					"Status":  http.StatusUnauthorized,
					"ErrorID": "mock",
				})
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"UserID":       "user-mock-1",
				"UID":          m.uid,
				"AccessToken":  m.accessToken,
				"RefreshToken": "refresh-mock-1",
				"ServerProof":  base64.StdEncoding.EncodeToString(serverProof),
				"Scope":        "user",
				"2FA":          map[string]any{"Enabled": m.twoFA, "FIDO2": map[string]any{}},
			})

		case "/auth/v4/2fa":
			_ = json.NewEncoder(w).Encode(map[string]any{"Code": 1000})

		default:
			t.Errorf("unexpected request to %s", r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	})
}

func runLoginWith(t *testing.T, apiURL, statePath, twoFactorCode string) (loginOutput, int) {
	t.Helper()
	in, err := json.Marshal(loginInput{
		Username:      mockUsername,
		Password:      mockPassword,
		TwoFactorCode: twoFactorCode,
		APIURL:        apiURL,
	})
	if err != nil {
		t.Fatalf("marshal input: %v", err)
	}
	var stdout, stderr bytes.Buffer
	code := runLogin([]string{"--state", statePath}, bytes.NewReader(in), &stdout, &stderr)
	var out loginOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v (stderr %q)", stdout.String(), err, stderr.String())
	}
	return out, code
}

func TestLoginStoresSessionForAPasswordOnlyAccount(t *testing.T) {
	mock := newMockAuthServer(t, 0)
	srv := httptest.NewServer(mock.handler(t))
	defer srv.Close()

	statePath := filepath.Join(t.TempDir(), "proton", "session.json")
	out, code := runLoginWith(t, srv.URL, statePath, "")

	if code != 0 || !out.OK {
		t.Fatalf("login failed: code=%d out=%+v", code, out)
	}
	if out.UID != mock.uid {
		t.Errorf("uid = %q, want %q", out.UID, mock.uid)
	}
	if mock.infoCalls != 1 || mock.authCalls != 1 {
		t.Errorf("endpoint calls = info %d, auth %d; want 1 and 1", mock.infoCalls, mock.authCalls)
	}

	raw, err := os.ReadFile(statePath)
	if err != nil {
		t.Fatalf("read session: %v", err)
	}
	var cred storedCredential
	if err := json.Unmarshal(raw, &cred); err != nil {
		t.Fatalf("parse session: %v", err)
	}
	if cred.AccessToken != mock.accessToken || cred.RefreshToken != "refresh-mock-1" {
		t.Errorf("session tokens = %q/%q, want the ones the API returned", cred.AccessToken, cred.RefreshToken)
	}
	if cred.ServerProof == "" {
		t.Error("session dropped the SRP server proof, so a later refresh cannot verify it")
	}
	if cred.TwoFactor {
		t.Error("twoFactor = true for an account without 2FA")
	}

	info, err := os.Stat(statePath)
	if err != nil {
		t.Fatalf("stat session: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("session mode = %o, want 600", perm)
	}
	dirPerm, err := os.Stat(filepath.Dir(statePath))
	if err != nil {
		t.Fatalf("stat session dir: %v", err)
	}
	if perm := dirPerm.Mode().Perm(); perm != 0o700 {
		t.Errorf("session dir mode = %o, want 700", perm)
	}
}

func TestLoginAsksForACodeBeforeStoringAnything(t *testing.T) {
	mock := newMockAuthServer(t, proton.HasTOTP)
	srv := httptest.NewServer(mock.handler(t))
	defer srv.Close()

	statePath := filepath.Join(t.TempDir(), "session.json")
	out, code := runLoginWith(t, srv.URL, statePath, "")

	if code == 0 || out.OK {
		t.Fatalf("login should not succeed without a TOTP code: code=%d out=%+v", code, out)
	}
	if !out.TwoFactorRequired {
		t.Errorf("twoFactorRequired = false, want true; out=%+v", out)
	}
	if out.Reason != "two-factor-required" {
		t.Errorf("reason = %q, want two-factor-required", out.Reason)
	}
	if _, err := os.Stat(statePath); !os.IsNotExist(err) {
		t.Errorf("session file was written for an unverified 2FA login (err=%v)", err)
	}
}

func TestLoginReportsAWrongPasswordWithoutEchoingIt(t *testing.T) {
	mock := newMockAuthServer(t, 0)
	srv := httptest.NewServer(mock.handler(t))
	defer srv.Close()

	in, err := json.Marshal(loginInput{
		Username: mockUsername,
		Password: "definitely-the-wrong-password",
		APIURL:   srv.URL,
	})
	if err != nil {
		t.Fatalf("marshal input: %v", err)
	}
	var stdout, stderr bytes.Buffer
	code := runLogin([]string{"--state", filepath.Join(t.TempDir(), "session.json")}, bytes.NewReader(in), &stdout, &stderr)

	var out loginOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v", stdout.String(), err)
	}
	if code == 0 || out.OK {
		t.Fatalf("login with a wrong password succeeded: code=%d out=%+v", code, out)
	}
	if out.Reason != "wrong-password" {
		t.Errorf("reason = %q, want wrong-password (code %d)", out.Reason, out.Code)
	}
	if bytes.Contains(stdout.Bytes(), []byte("definitely-the-wrong-password")) ||
		bytes.Contains(stderr.Bytes(), []byte("definitely-the-wrong-password")) {
		t.Error("the password was echoed back in the output")
	}
	if _, err := os.Stat(filepath.Join(t.TempDir(), "session.json")); err == nil {
		t.Error("a session file exists after a failed login")
	}
}
