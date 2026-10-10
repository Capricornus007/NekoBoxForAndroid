package localapi

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const testToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type handlerFunc func(string, string) string

func (f handlerFunc) Call(operation, parameters string) string { return f(operation, parameters) }

func TestTransportBoundary(t *testing.T) {
	cases := []struct {
		name, method, path, body, auth, host, contentType, header string
		status                                                    int
	}{
		{"describe", "GET", "/v1", "", "Bearer " + testToken, "127.0.0.1:9091", "", "", 200},
		{"command", "POST", "/v1/profiles.list", "{}", "Bearer " + testToken, "127.0.0.1:19091", "application/json", "", 200},
		{"missing token", "GET", "/v1", "", "", "127.0.0.1:9091", "", "", 401},
		{"wrong token", "GET", "/v1", "", "Bearer wrong", "127.0.0.1:9091", "", "", 401},
		{"query token", "GET", "/v1?token=" + testToken, "", "", "127.0.0.1:9091", "", "", 401},
		{"rebind", "GET", "/v1", "", "Bearer " + testToken, "example.org:9091", "", "", 403},
		{"missing port", "GET", "/v1", "", "Bearer " + testToken, "127.0.0.1", "", "", 403},
		{"invalid port", "GET", "/v1", "", "Bearer " + testToken, "127.0.0.1:65536", "", "", 403},
		{"origin", "GET", "/v1", "", "Bearer " + testToken, "127.0.0.1:9091", "", "Origin", 403},
		{"browser", "GET", "/v1", "", "Bearer " + testToken, "127.0.0.1:9091", "", "Sec-Fetch-Site", 403},
		{"preflight", "OPTIONS", "/v1", "", "Bearer " + testToken, "127.0.0.1:9091", "", "Origin", 403},
		{"query", "GET", "/v1?extra=true", "", "Bearer " + testToken, "127.0.0.1:9091", "", "", 400},
		{"get mutation", "GET", "/v1/profiles.delete", "", "Bearer " + testToken, "127.0.0.1:9091", "", "", 404},
		{"get body", "GET", "/v1", "{}", "Bearer " + testToken, "127.0.0.1:9091", "", "", 400},
		{"form", "POST", "/v1/profiles.list", "{}", "Bearer " + testToken, "127.0.0.1:9091", "text/plain", "", 415},
		{"truncated", "POST", "/v1/profiles.list", "{", "Bearer " + testToken, "127.0.0.1:9091", "application/json", "", 400},
		{"trailing", "POST", "/v1/profiles.list", "{}{}", "Bearer " + testToken, "127.0.0.1:9091", "application/json", "", 400},
		{"array", "POST", "/v1/profiles.list", "[]", "Bearer " + testToken, "127.0.0.1:9091", "application/json", "", 400},
		{"null", "POST", "/v1/profiles.list", "null", "Bearer " + testToken, "127.0.0.1:9091", "application/json", "", 400},
		{"escaped operation", "POST", "/v1/profiles%2elist", "{}", "Bearer " + testToken, "127.0.0.1:9091", "application/json", "", 400},
		{"large body", "POST", "/v1/profiles.list", `{"value":"` + strings.Repeat("x", MaxRequestBytes) + `"}`, "Bearer " + testToken, "127.0.0.1:9091", "application/json", "", 413},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			calls := 0
			handler := newHandler(testToken, handlerFunc(func(operation, parameters string) string {
				calls++
				if operation != "api.describe" && operation != "profiles.list" {
					t.Fatalf("unexpected operation %q", operation)
				}
				return `{"result":{}}`
			}))
			r := httptest.NewRequest(c.method, c.path, strings.NewReader(c.body))
			r.Host = c.host
			if c.auth != "" {
				r.Header.Set("Authorization", c.auth)
			}
			r.Header.Set("Content-Type", c.contentType)
			if c.header != "" {
				r.Header.Set(c.header, "null")
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != c.status {
				t.Fatalf("status %d, want %d: %s", w.Code, c.status, w.Body)
			}
			wantCalls := 0
			if c.status == 200 {
				wantCalls = 1
			}
			if calls != wantCalls {
				t.Fatalf("handler calls %d, want %d", calls, wantCalls)
			}
			if w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Access-Control-Allow-Origin") != "" {
				t.Fatal("unsafe response headers")
			}
		})
	}
}

func TestJSONComplexityLimits(t *testing.T) {
	if !boundedJSON([]byte(`{"values":[1,2,{"name":"x"}]}`)) {
		t.Fatal("rejected ordinary parameters")
	}
	deep := strings.Repeat(`{"a":`, 65) + `0` + strings.Repeat(`}`, 65)
	if boundedJSON([]byte(deep)) {
		t.Fatal("accepted excessive nesting")
	}
	large := `{"values":[` + strings.Repeat(`0,`, 100000) + `0]}`
	if boundedJSON([]byte(large)) {
		t.Fatal("accepted excessive token count")
	}
}

func TestHandlerFailures(t *testing.T) {
	for name, callback := range map[string]handlerFunc{
		"panic":            func(string, string) string { panic("private value") },
		"invalid response": func(string, string) string { return "private value" },
		"large response":   func(string, string) string { return strings.Repeat("x", MaxResponseBytes+1) },
	} {
		t.Run(name, func(t *testing.T) {
			h := newHandler(testToken, callback)
			r := httptest.NewRequest("GET", "http://127.0.0.1:9091/v1", nil)
			r.Header.Set("Authorization", "Bearer "+testToken)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != 500 || strings.Contains(w.Body.String(), "private value") {
				t.Fatalf("unsafe failure: %d %s", w.Code, w.Body)
			}
		})
	}
}

func TestDuplicateAuthorizationAndBusy(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	h := newHandler(testToken, handlerFunc(func(string, string) string {
		close(entered)
		<-release
		return `{"result":{}}`
	}))
	r := httptest.NewRequest("GET", "http://127.0.0.1:9091/v1", nil)
	r.Header.Add("Authorization", "Bearer "+testToken)
	r.Header.Add("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("accepted duplicate authorization")
	}
	r.Header.Set("Authorization", "Bearer "+testToken)
	done := make(chan struct{})
	go func() { defer close(done); h.ServeHTTP(httptest.NewRecorder(), r) }()
	<-entered
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r.Clone(r.Context()))
	close(release)
	<-done
	if w.Code != 503 {
		t.Fatalf("concurrent request status: %d", w.Code)
	}
}

func TestLoopbackLifecycle(t *testing.T) {
	h := handlerFunc(func(string, string) string { return `{"result":{}}` })
	for _, token := range []string{"", "short", strings.Repeat("z", 64)} {
		if _, err := Start(0, token, h); err == nil {
			t.Fatal("accepted invalid token")
		}
	}
	s, err := Start(0, testToken, h)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if !strings.HasPrefix(s.listener.Addr().String(), "127.0.0.1:") {
		t.Fatalf("non-loopback listener: %s", s.listener.Addr())
	}
	req, _ := http.NewRequest("GET", "http://"+s.listener.Addr().String()+"/v1", nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || response.StatusCode != 200 || string(body) != `{"result":{}}` {
		t.Fatalf("unexpected response: %v %s", err, body)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
}
