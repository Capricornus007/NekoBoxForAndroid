package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	proton "github.com/ProtonMail/go-proton-api"
	srp "github.com/ProtonMail/go-srp"
)

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
