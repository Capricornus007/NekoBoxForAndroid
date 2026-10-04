package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func writeTestSession(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "session.json")
	payload, err := json.Marshal(storedCredential{
		UID:          "uid-test",
		UserID:       "user-test",
		AccessToken:  "token-test",
		RefreshToken: "refresh-test",
	})
	if err != nil {
		t.Fatalf("marshal session: %v", err)
	}
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		t.Fatalf("write session: %v", err)
	}
	return path
}

// apiURLForSession is how the test points the client at the mock: nodes reads the
// URL from a flag rather than the environment so the production default stays put.
func runNodesWith(t *testing.T, apiURL, statePath string, extra ...string) (nodesOutput, int) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	args := append([]string{"--state", statePath, "--api-url", apiURL}, extra...)
	code := runNodes(args, &stdout, &stderr)
	var out nodesOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v (stderr %q)", stdout.String(), err, stderr.String())
	}
	return out, code
}

func TestNodesSelectsUsableServersAndDropsKeylessOnes(t *testing.T) {
	var gotAuth, gotUID, gotAPIVersion string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotUID = r.Header.Get("x-pm-uid")
		gotAPIVersion = r.Header.Get("x-pm-apiversion")
		if r.URL.Path != "/vpn/logicals" {
			t.Errorf("path = %q, want /vpn/logicals", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(logicalServersResp{LogicalServers: []logicalServer{
			{ID: 10, Name: "JP#1 拥挤", Load: 70, Tier: 2, Country: "jp", State: "up", Servers: []physic{
				{EntryIP: "1.2.3.4:51820", Domain: "jp1.protonvpn.net", WgPublicKey: "AAAACl=="},
			}},
			{ID: 11, Name: "JP#2 空閒", Load: 15, Tier: 2, Country: "jp", State: "up", Servers: []physic{
				{EntryIP: "1.2.3.5:51820", Domain: "jp2.protonvpn.net", WgPublicKey: "BBBBAl=="},
			}},
			// No WireGuard key at all: must be dropped, never surfaced as a node.
			{ID: 12, Name: "無公鑰", Load: 1, Tier: 2, Country: "jp", State: "up", Servers: []physic{
				{EntryIP: "1.2.3.6:51820", Domain: "jp3.protonvpn.net"},
			}},
			{ID: 13, Name: "離線", Load: 2, Tier: 0, Country: "jp", State: "down", Servers: []physic{
				{EntryIP: "1.2.3.7:51820", WgPublicKey: "DDDDCl=="},
			}},
		}})
	}))
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t))
	if code != 0 || !out.OK {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	if len(out.Servers) != 2 {
		t.Fatalf("got %d servers, want 2 usable: %+v", len(out.Servers), out.Servers)
	}
	if out.Dropped != 2 {
		t.Errorf("dropped = %d, want 2 (one keyless, one down)", out.Dropped)
	}
	if out.Servers[0].ID != 11 || out.Servers[1].ID != 10 {
		t.Errorf("order = %d,%d; want load-ascending 11,10", out.Servers[0].ID, out.Servers[1].ID)
	}
	if out.Servers[0].PublicKey != "BBBBAl==" {
		t.Errorf("public key = %q, want the one the API returned", out.Servers[0].PublicKey)
	}
	for _, s := range out.Servers {
		if s.PublicKey == "" {
			t.Errorf("server %d was emitted without a WireGuard key", s.ID)
		}
	}
	if gotAuth != "Bearer token-test" {
		t.Errorf("Authorization = %q, want the bearer form of the stored token", gotAuth)
	}
	if gotUID != "uid-test" {
		t.Errorf("x-pm-uid = %q, want uid-test", gotUID)
	}
	if gotAPIVersion != protonAPIVersion {
		t.Errorf("x-pm-apiversion = %q, want %q", gotAPIVersion, protonAPIVersion)
	}
}

func TestNodesAcceptsEveryObservedKeySpelling(t *testing.T) {
	cases := []struct {
		name string
		p    physic
		want string
	}{
		{"WgPublicKey", physic{WgPublicKey: "A1=="}, "A1=="},
		{"WrGwPublicKey", physic{WrGwPublicKey: "A2=="}, "A2=="},
		{"wg_public_key", physic{WgPublicKeyLow: "A3=="}, "A3=="},
		{"none", physic{}, ""},
	}
	for _, tc := range cases {
		if got := tc.p.publicKey(); got != tc.want {
			t.Errorf("%s: publicKey() = %q, want %q", tc.name, got, tc.want)
		}
	}
}

func TestNodesReportsAnExpiredSessionInsteadOfFakeNodes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"Code":10013,"Error":"Invalid access token"}`))
	}))
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t))
	if code == 0 || out.OK {
		t.Fatalf("nodes reported success against a rejected token: %+v", out)
	}
	if len(out.Servers) != 0 {
		t.Errorf("servers = %d, want none after an auth rejection", len(out.Servers))
	}
	if out.Error != errSessionExpired.Error() {
		t.Errorf("error = %q, want the re-login hint %q", out.Error, errSessionExpired.Error())
	}
}

func TestNodesRefusesToInventNodesFromAnEmptyList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"LogicalServers":[]}`))
	}))
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t))
	if code == 0 || out.OK {
		t.Fatalf("empty list reported success: %+v", out)
	}
	if out.Error == "" {
		t.Error("empty list failed without an explanation")
	}
}

func TestNodesCountryFilterAndLimit(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(logicalServersResp{LogicalServers: []logicalServer{
			{ID: 1, Name: "JP", Load: 10, Country: "jp", State: "up", Servers: []physic{{WgPublicKey: "a=="}}},
			{ID: 2, Name: "US", Load: 20, Country: "us", State: "up", Servers: []physic{{WgPublicKey: "b=="}}},
			{ID: 3, Name: "JP2", Load: 30, Country: "JP", State: "up", Servers: []physic{{WgPublicKey: "c=="}}},
		}})
	}))
	defer srv.Close()

	out, _ := runNodesWith(t, srv.URL, writeTestSession(t), "--country", "JP")
	if len(out.Servers) != 2 {
		t.Fatalf("country filter gave %d servers, want 2: %+v", len(out.Servers), out.Servers)
	}
	for _, s := range out.Servers {
		if s.Country != "jp" && s.Country != "JP" {
			t.Errorf("server %d has country %q inside a JP filter", s.ID, s.Country)
		}
	}

	out, _ = runNodesWith(t, srv.URL, writeTestSession(t), "--limit", "1")
	if len(out.Servers) != 1 {
		t.Fatalf("limit 1 gave %d servers: %+v", len(out.Servers), out.Servers)
	}
	if out.Servers[0].ID != 1 {
		t.Errorf("limit kept ID %d, want the lowest-load 1", out.Servers[0].ID)
	}
}
