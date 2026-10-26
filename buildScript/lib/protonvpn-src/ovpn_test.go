package main

import (
	"bytes"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestGenerateOpenVPNIdentityProducesParsablePEM(t *testing.T) {
	privPEM, pubPEM, err := generateOpenVPNIdentity("nb4a-openvpn")
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	privBlock, _ := pem.Decode([]byte(privPEM))
	if privBlock == nil || privBlock.Type != "RSA PRIVATE KEY" {
		t.Fatalf("private pem = %v", privBlock)
	}
	key, err := x509.ParsePKCS1PrivateKey(privBlock.Bytes)
	if err != nil {
		t.Fatalf("parse private: %v", err)
	}
	if key.N.BitLen() != openvpnKeyBits {
		t.Errorf("key size = %d, want the %d Proton's CA signs", key.N.BitLen(), openvpnKeyBits)
	}
	certBlock, _ := pem.Decode([]byte(pubPEM))
	if certBlock == nil || certBlock.Type != "CERTIFICATE" {
		t.Fatalf("public pem = %v", certBlock)
	}
	csr, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		t.Fatalf("parse self-signed: %v", err)
	}
	if csr.Subject.CommonName != "nb4a-openvpn" {
		t.Errorf("CN = %q, want the device name Proton lists", csr.Subject.CommonName)
	}
}

func TestOvpnCommandWritesMaterialAndConfig(t *testing.T) {
	var gotPath, gotAuth string
	var gotBody map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotAuth = r.Header.Get("Authorization")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"Code":1000,"SerialNumber":"1234","ClientKey":"` +
			strings.ReplaceAll(gotBody["ClientPublicKey"], "\n", "\\n") +
			`","Certificate":"-----BEGIN CERTIFICATE-----\nSIGNED\n-----END CERTIFICATE-----\n",` +
			`"ExpirationTime":4000000000,"RefreshTime":3999990000,"Mode":"secure-core"}`))
	}))
	defer srv.Close()

	dir := t.TempDir()
	outDir := filepath.Join(dir, "openvpn")
	statePath := writeTestSession(t, dir)
	var stdout, stderr bytes.Buffer
	code := runOvpn([]string{"--state", statePath, "--out-dir", outDir, "--api-url", srv.URL,
		"--host", "ch-ll-007.protonvpn.net", "--port", "1194", "--proto", "udp",
		"--username", "tester@proton.me"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("runOvpn exit=%d out=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	if gotPath != "/vpn/v1/certificate" {
		t.Errorf("path = %q, want /vpn/v1/certificate", gotPath)
	}
	if gotAuth != "Bearer token-test" {
		t.Errorf("Authorization = %q, want the stored bearer token", gotAuth)
	}
	if !strings.Contains(gotBody["ClientPublicKey"], "BEGIN CERTIFICATE") {
		t.Errorf("ClientPublicKey = %q, want the self-signed PEM", gotBody["ClientPublicKey"])
	}

	var out ovpnOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse %q: %v", stdout.String(), err)
	}
	if !out.OK || out.ExpiresAt != 4000000000 {
		t.Fatalf("out = %+v, want ok with the API expiry", out)
	}
	for _, want := range []string{"remote ch-ll-007.protonvpn.net 1194 udp", "cipher AES-256-GCM",
		"<tls-crypt>", "<ca>", "<cert>", "<key>", "auth-user-pass", `setenv USERNAME "tester@proton.me"`,
		"remote-cert-tls server", "SIGNED"} {
		if !strings.Contains(out.Config, want) {
			t.Errorf("config missing %q:\n%s", want, out.Config)
		}
	}
	for _, p := range []string{out.KeyPath, out.CertPath, out.CAPath, out.TLSKeyPath} {
		info, err := os.Stat(p)
		if err != nil {
			t.Fatalf("expected %q written: %v", p, err)
		}
		if info.Mode().Perm() != 0o600 {
			t.Errorf("%s mode = %v, want 0600", filepath.Base(p), info.Mode().Perm())
		}
	}
	if b, _ := os.ReadFile(out.CertPath); !strings.Contains(string(b), "SIGNED") {
		t.Errorf("client.crt = %q, want the certificate Proton signed", b)
	}
	if b, _ := os.ReadFile(out.CAPath); !strings.Contains(string(b), "BEGIN CERTIFICATE") {
		t.Error("ca.crt must carry Proton's CA from the official template")
	}
}

func TestOvpnCommandRejectsUnknownProto(t *testing.T) {
	dir := t.TempDir()
	var stdout, stderr bytes.Buffer
	code := runOvpn([]string{"--state", writeTestSession(t, dir), "--out-dir", dir,
		"--host", "x.protonvpn.net", "--port", "443", "--proto", "sctp"}, &stdout, &stderr)
	if code != 2 {
		t.Fatalf("exit=%d, want usage error for a proto the API cannot dial", code)
	}
}

func TestOvpnCommandReportsExpiryWhenSessionRejected(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/auth/v4/refresh" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"Code":10013,"Error":"Invalid refresh token"}`))
			return
		}
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"Code":10013,"Error":"Invalid access token"}`))
	}))
	defer srv.Close()

	dir := t.TempDir()
	var stdout, stderr bytes.Buffer
	code := runOvpn([]string{"--state", writeTestSession(t, dir), "--out-dir", dir, "--api-url", srv.URL,
		"--host", "x.protonvpn.net", "--port", "1194"}, &stdout, &stderr)
	if code == 0 {
		t.Fatalf("exit=0 with a rejected session, want failure")
	}
	var out ovpnOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse %q: %v", stdout.String(), err)
	}
	if out.OK || out.Code != codeSessionExpired {
		t.Errorf("out = %+v, want code %s so the app can route to sign-in", out, codeSessionExpired)
	}
}

// TestOvpnConfigOmitsAuthUserPassWithoutUsername 釘住官方模板的條件式寫法：
// 沒有帳密就不發 auth-user-pass，避免把「一定要密碼」這個假設塞進剖面。
func TestOvpnConfigOmitsAuthUserPassWithoutUsername(t *testing.T) {
	cfg := openvpnConfig("nl-001.protonvpn.net", 443, "tcp", "PRIV", "CERT", "")
	if strings.Contains(cfg, "auth-user-pass") || strings.Contains(cfg, "setenv USERNAME") {
		t.Errorf("config without username still carries auth-user-pass:\n%s", cfg)
	}
	for _, want := range []string{"remote nl-001.protonvpn.net 443 tcp", "<cert>", "CERT", "PRIV"} {
		if !strings.Contains(cfg, want) {
			t.Errorf("config missing %q", want)
		}
	}
}
