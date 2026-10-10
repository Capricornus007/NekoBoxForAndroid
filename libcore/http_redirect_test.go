package libcore

import (
	"crypto/x509"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// redirectRecorder redirects the paths in routes to their targets, serves a subscription body
// for any other path and records the headers each request arrived with.
type redirectRecorder struct {
	mu       sync.Mutex
	routes   map[string]string
	requests []http.Header
}

func newRedirectRecorder() *redirectRecorder {
	return &redirectRecorder{routes: map[string]string{}}
}

func (r *redirectRecorder) route(path, target string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.routes[path] = target
}

func (r *redirectRecorder) ServeHTTP(w http.ResponseWriter, request *http.Request) {
	r.mu.Lock()
	r.requests = append(r.requests, request.Header.Clone())
	target := r.routes[request.URL.Path]
	r.mu.Unlock()
	if target != "" {
		http.Redirect(w, request, target, http.StatusFound)
		return
	}
	_, _ = w.Write([]byte("socks://192.0.2.1:1080#node"))
}

func (r *redirectRecorder) received() []http.Header {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]http.Header(nil), r.requests...)
}

func fetchWithDeviceHeaders(t *testing.T, roots *x509.CertPool, link string) (string, error) {
	t.Helper()
	client := NewHttpClient().(*httpClient)
	defer client.Close()
	client.tls.RootCAs = roots
	request := client.NewRequest()
	if err := request.SetURL(link); err != nil {
		t.Fatal(err)
	}
	request.SetUserAgent("subscription-test")
	request.SetHeader("X-HWID", "test-device")
	response, err := request.Execute()
	if err != nil {
		return "", err
	}
	return response.GetContentStringLimited(1024)
}

// tlsServers starts HTTPS test servers, which differ only in port, and a pool that trusts them.
func tlsServers(t *testing.T, count int) ([]*redirectRecorder, []*httptest.Server, *x509.CertPool) {
	t.Helper()
	recorders := make([]*redirectRecorder, count)
	servers := make([]*httptest.Server, count)
	for i := range servers {
		recorders[i] = newRedirectRecorder()
		servers[i] = httptest.NewTLSServer(recorders[i])
		t.Cleanup(servers[i].Close)
	}
	// httptest serves every TLS server with one test certificate; trusting it keeps verification on.
	roots := x509.NewCertPool()
	roots.AddCert(servers[0].Certificate())
	return recorders, servers, roots
}

func TestSubscriptionRedirectsNeverLeaveHTTPSOrCarryCredentialsAway(t *testing.T) {
	plain := newRedirectRecorder()
	plainServer := httptest.NewServer(plain)
	defer plainServer.Close()
	recorders, servers, roots := tlsServers(t, 2)
	origin, other := recorders[0], recorders[1]
	link := strings.Replace(servers[0].URL, "https://", "https://reader:example-secret@", 1) + "/sub"

	// A downgrade fails before the plain server is contacted.
	origin.route("/sub", plainServer.URL+"/list")
	if _, err := fetchWithDeviceHeaders(t, roots, link); !errors.Is(err, errRedirectDowngrade) {
		t.Fatal("a redirect to plain HTTP was not refused")
	}
	if got := len(plain.received()); got != 0 {
		t.Fatalf("plain server received %d requests", got)
	}

	// Another HTTPS origin gets the request without credentials or the device identifier.
	origin.route("/sub", servers[1].URL+"/list")
	if body, err := fetchWithDeviceHeaders(t, roots, link); err != nil || body == "" {
		t.Fatal("cross-origin redirect failed")
	}
	forwarded := other.received()
	if len(forwarded) != 1 {
		t.Fatalf("other origin received %d requests", len(forwarded))
	}
	for _, key := range []string{"Authorization", "X-HWID"} {
		if forwarded[0].Get(key) != "" {
			t.Fatalf("cross-origin hop carried %s", key)
		}
	}
	if forwarded[0].Get("User-Agent") != "subscription-test" {
		t.Fatal("cross-origin hop lost the User-Agent")
	}

	// The same origin keeps both.
	origin.route("/sub", servers[0].URL+"/list")
	if body, err := fetchWithDeviceHeaders(t, roots, link); err != nil || body == "" {
		t.Fatal("same-origin redirect failed")
	}
	same := origin.received()
	last := same[len(same)-1]
	if last.Get("Authorization") == "" || last.Get("X-HWID") != "test-device" {
		t.Fatal("same-origin hop lost credentials or the device identifier")
	}
}

func TestRedirectsNeverCarryAReferrerToAnotherOrigin(t *testing.T) {
	recorders, servers, roots := tlsServers(t, 3)
	origin, relay, last := recorders[0], recorders[1], recorders[2]
	const originToken, relayToken = "origin-secret-token", "relay-secret-token"
	carries := func(header http.Header, token string) bool {
		return strings.Contains(header.Get("Referer"), token)
	}

	// The subscription path and query hold tokens; another origin gets no Referer at all.
	origin.route("/sub/"+originToken, servers[1].URL+"/hop")
	link := servers[0].URL + "/sub/" + originToken + "?key=" + originToken
	if _, err := fetchWithDeviceHeaders(t, roots, link); err != nil {
		t.Fatal("redirect to another port failed")
	}
	if got := relay.received(); len(got) != 1 || got[0].Get("Referer") != "" {
		t.Fatal("a hop to another port carried a Referer")
	}

	// Through an intermediary and back, the return hop must not carry the intermediary's URL,
	// nor any hop the first one's.
	relay.route("/relay/"+relayToken, servers[0].URL+"/final")
	origin.route("/sub/"+originToken, servers[1].URL+"/relay/"+relayToken+"?key="+relayToken)
	before := len(origin.received())
	if _, err := fetchWithDeviceHeaders(t, roots, link); err != nil {
		t.Fatal("redirect chain through an intermediary failed")
	}
	returned := origin.received()[before:]
	if len(returned) != 2 {
		t.Fatalf("origin received %d requests in the chain", len(returned))
	}
	if carries(returned[1], relayToken) || returned[1].Get("Referer") != "" {
		t.Fatal("the return hop carried the intermediary's URL")
	}
	if relayed := relay.received(); carries(relayed[len(relayed)-1], originToken) {
		t.Fatal("the intermediary received the first URL")
	}

	// A chain of several origins strips it on every hop.
	relay.route("/relay/"+relayToken, servers[2].URL+"/final")
	if _, err := fetchWithDeviceHeaders(t, roots, link); err != nil {
		t.Fatal("multi-hop redirect chain failed")
	}
	if got := last.received(); len(got) != 1 || got[0].Get("Referer") != "" {
		t.Fatal("the last hop of a chain carried a Referer")
	}

	// A hop on the same origin may keep it: the URL stays with the server that issued it.
	origin.route("/sub/"+originToken, servers[0].URL+"/list")
	before = len(origin.received())
	if _, err := fetchWithDeviceHeaders(t, roots, link); err != nil {
		t.Fatal("same-origin redirect failed")
	}
	if same := origin.received()[before:]; len(same) != 2 || !carries(same[1], originToken) {
		t.Fatal("a same-origin hop lost its Referer")
	}
}

func TestRedirectToAnotherHostDropsTheReferrer(t *testing.T) {
	target := newRedirectRecorder()
	targetServer := httptest.NewServer(target)
	defer targetServer.Close()
	origin := newRedirectRecorder()
	originServer := httptest.NewServer(origin)
	defer originServer.Close()
	// Same address, another host name: the origin differs only by host.
	other := strings.Replace(targetServer.URL, "127.0.0.1", "localhost", 1)
	origin.route("/sub", other+"/list")
	if _, err := fetchWithDeviceHeaders(t, nil, originServer.URL+"/sub?key=host-secret-token"); err != nil {
		t.Fatal("redirect to another host failed")
	}
	if got := target.received(); len(got) != 1 || got[0].Get("Referer") != "" || got[0].Get("X-HWID") != "" {
		t.Fatal("a hop to another host carried the Referer or the device identifier")
	}
}
