package libcore

import (
	"context"
	"crypto/x509"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

func TestDecodeLocationAcceptsOnlyFiniteCoordinatesInRange(t *testing.T) {
	for _, input := range []string{
		`{}`, `not json`, `{"latitude":91,"longitude":0}`, `{"latitude":0,"longitude":-181}`,
		`{"latitude":null,"longitude":0}`, `{"latitude":"1","longitude":0}`, `{"latitude":1e400,"longitude":0}`,
		`{"latitude":0,"longitude":0} trailing`,
	} {
		if _, err := decodeLocation([]byte(input)); err == nil {
			t.Fatalf("accepted invalid coordinates: %s", input)
		}
	}
	for input, want := range map[string]string{
		`{"ip":"192.0.2.1","latitude":0,"longitude":0}`: "0,0",
		`{"latitude":-90,"longitude":180}`:              "-90,180",
		`{"latitude":1e-7,"longitude":52.52}`:           "0.0000001,52.52",
	} {
		if got, err := decodeLocation([]byte(input)); err != nil || got != want {
			t.Fatalf("decodeLocation(%s) = %q, %v; want %q", input, got, err, want)
		}
	}
}

func TestLocationURLRequiresHTTPSWithoutCredentialsOrFragment(t *testing.T) {
	for _, link := range []string{
		"http://example.com/json/", "https://user:password@example.com/", "https://user@example.com/",
		"https://example.com/#fragment", "https:///json/", "example.com/json/",
	} {
		if validateLocationURL(link) == nil {
			t.Fatalf("accepted %s", link)
		}
	}
	for _, link := range []string{"https://ipapi.co/json/", "https://example.com:8443/geo?fields=lat,lon"} {
		if err := validateLocationURL(link); err != nil {
			t.Fatalf("rejected %s: %v", link, err)
		}
	}
}

// newLocationTestInstance runs a core with a direct and a block outbound and no platform services.
func newLocationTestInstance(t *testing.T) *BoxInstance {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	config := `{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct"},{"type":"block","tag":"block"}]}`
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		cancel()
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	b := &BoxInstance{Box: instance, ctx: ctx, cancel: cancel, state: 1, running: true}
	t.Cleanup(func() { b.Close() })
	// PreStart starts the outbounds without opening the Android TUN interface.
	if err := instance.PreStart(); err != nil {
		t.Fatal(err)
	}
	return b
}

func newLocationTestServer(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	server := httptest.NewTLSServer(handler)
	t.Cleanup(server.Close)
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	previous := locationRootCAs
	locationRootCAs = roots
	t.Cleanup(func() { locationRootCAs = previous })
	return server
}

func TestLocationLookupLeavesOnlyThroughTheNamedOutbound(t *testing.T) {
	var hits atomic.Int32
	server := newLocationTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		io.WriteString(w, `{"ip":"192.0.2.1","latitude":52.52,"longitude":13.405}`)
	})
	instance := newLocationTestInstance(t)
	if got, err := LookupOutboundLocation(instance, "direct", server.URL+"/json/"); err != nil || got != "52.52,13.405" {
		t.Fatalf("lookup = %q, %v", got, err)
	}
	for _, tag := range []string{"block", "missing"} {
		if _, err := LookupOutboundLocation(instance, tag, server.URL+"/json/"); err == nil {
			t.Fatalf("lookup through %s succeeded", tag)
		}
	}
	if hits.Load() != 1 {
		t.Fatalf("server saw %d requests, want only the one through direct", hits.Load())
	}
}

func TestLocationLookupRejectsRedirectsErrorsAndOversizedResponses(t *testing.T) {
	var redirected atomic.Int32
	server := newLocationTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/redirect":
			http.Redirect(w, r, "/target", http.StatusFound)
		case "/target":
			redirected.Add(1)
			io.WriteString(w, `{"latitude":1,"longitude":1}`)
		case "/unavailable":
			w.WriteHeader(http.StatusTooManyRequests)
			io.WriteString(w, `{"latitude":1,"longitude":1}`)
		case "/oversized":
			io.WriteString(w, `{"latitude":1,"longitude":1,"padding":"`+strings.Repeat("x", locationResponseLimit)+`"}`)
		}
	})
	instance := newLocationTestInstance(t)
	for _, path := range []string{"/redirect", "/unavailable", "/oversized"} {
		if _, err := LookupOutboundLocation(instance, "direct", server.URL+path); err == nil {
			t.Fatalf("lookup of %s succeeded", path)
		}
	}
	if redirected.Load() != 0 {
		t.Fatal("redirect was followed")
	}
}

func TestLocationLookupEndsWithTheInstance(t *testing.T) {
	entered := make(chan struct{})
	server := newLocationTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done()
	})
	instance := newLocationTestInstance(t)
	result := make(chan error, 1)
	go func() {
		_, err := LookupOutboundLocation(instance, "direct", server.URL)
		result <- err
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("lookup never reached the server")
	}
	closed := make(chan struct{})
	go func() {
		instance.Close()
		close(closed)
	}()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("cancelled lookup reported success")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("closing the instance did not cancel the lookup")
	}
	select {
	case <-closed:
	case <-time.After(2 * time.Second):
		t.Fatal("close did not finish after the lookup ended")
	}
	if _, err := LookupOutboundLocation(instance, "direct", server.URL); err == nil {
		t.Fatal("lookup admitted after close")
	}
}
