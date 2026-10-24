package libcore

import (
	"archive/zip"
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route/rule"
	"github.com/ulikunitz/xz"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	utls "github.com/metacubex/utls"
	M "github.com/sagernet/sing/common/metadata"
)

const raceTestTimeout = 2 * time.Second

type trackingReadCloser struct {
	reader io.Reader
	closed chan struct{}
	once   sync.Once
}

func newTrackingReadCloser(content string) *trackingReadCloser {
	return &trackingReadCloser{
		reader: strings.NewReader(content),
		closed: make(chan struct{}),
	}
}

func (r *trackingReadCloser) Read(p []byte) (int, error) {
	return r.reader.Read(p)
}

func (r *trackingReadCloser) Close() error {
	r.once.Do(func() { close(r.closed) })
	return nil
}

type httpRaceResult struct {
	response *http.Response
	err      error
}

func waitForSignal(t *testing.T, signal <-chan struct{}, description string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(raceTestTimeout):
		t.Fatalf("timed out waiting for %s", description)
	}
}

func waitForWorkers(t *testing.T, workers *sync.WaitGroup) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		workers.Wait()
		close(done)
	}()
	waitForSignal(t, done, "request functions")
}

func waitForRaceResult(t *testing.T, resultCh <-chan httpRaceResult) httpRaceResult {
	t.Helper()
	select {
	case result := <-resultCh:
		return result
	case <-time.After(raceTestTimeout):
		t.Fatal("timed out waiting for request race")
		return httpRaceResult{}
	}
}

func assertBodyClosed(t *testing.T, body *trackingReadCloser) {
	t.Helper()
	waitForSignal(t, body.closed, "response body close")
}

func TestRaceHTTPRequestsTimeoutCancelsWorkersAndClosesBodies(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	firstBody := newTrackingReadCloser("first")
	secondBody := newTrackingReadCloser("second")
	var workers sync.WaitGroup
	workers.Add(2)

	blockedRequest := func(body *trackingReadCloser) requestFunc {
		return func(requestCtx context.Context) (*http.Response, error) {
			defer workers.Done()
			<-requestCtx.Done()
			return &http.Response{StatusCode: http.StatusOK, Body: body}, nil
		}
	}

	resultCh := make(chan httpRaceResult, 1)
	go func() {
		response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
			{label: "first", request: blockedRequest(firstBody)},
			{label: "second", request: blockedRequest(secondBody)},
		})
		resultCh <- httpRaceResult{response: response, err: err}
	}()

	result := waitForRaceResult(t, resultCh)
	if result.response != nil {
		t.Fatal("timeout returned a response")
	}
	if !errors.Is(result.err, context.DeadlineExceeded) {
		t.Fatalf("timeout error = %v, want context deadline exceeded", result.err)
	}
	waitForWorkers(t, &workers)
	assertBodyClosed(t, firstBody)
	assertBodyClosed(t, secondBody)
}

func TestRaceHTTPRequestsEmptyResponse(t *testing.T) {
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "empty",
			request: func(context.Context) (*http.Response, error) {
				return nil, nil
			},
		},
	})

	if response != nil {
		t.Fatal("empty result returned a response")
	}
	if err == nil || !strings.Contains(err.Error(), "empty response") {
		t.Fatalf("empty result error = %v, want empty response", err)
	}
}

func TestRaceHTTPRequestsWorkerPanicIsReturned(t *testing.T) {
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "panicked",
			request: func(context.Context) (*http.Response, error) {
				panic("worker panic")
			},
		},
	})

	if response != nil {
		t.Fatal("panicked request returned a response")
	}
	if err == nil || !strings.Contains(err.Error(), "panicked: http panic: worker panic") {
		t.Fatalf("panic error = %v, want labeled worker panic", err)
	}
}

func TestRaceHTTPRequestsFirstSuccessCancelsLoserAndKeepsWinnerReadable(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	winnerBody := newTrackingReadCloser("winner")
	lateBody := newTrackingReadCloser("late")
	lateStarted := make(chan struct{})
	lateReturned := make(chan struct{})
	var winnerRequestDone <-chan struct{}

	response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
		{
			label: "winner",
			request: func(requestCtx context.Context) (*http.Response, error) {
				winnerRequestDone = requestCtx.Done()
				<-lateStarted
				return &http.Response{StatusCode: http.StatusOK, Body: winnerBody}, nil
			},
		},
		{
			label: "late",
			request: func(requestCtx context.Context) (*http.Response, error) {
				close(lateStarted)
				<-requestCtx.Done()
				close(lateReturned)
				return &http.Response{StatusCode: http.StatusOK, Body: lateBody}, nil
			},
		},
	})
	if err != nil {
		cancel()
		t.Fatalf("request race failed: %v", err)
	}
	if response == nil {
		cancel()
		t.Fatal("request race returned no response")
	}

	waitForSignal(t, lateReturned, "late request function")
	assertBodyClosed(t, lateBody)
	cancel()

	select {
	case <-winnerBody.closed:
		t.Fatal("winning response body was closed")
	case <-winnerRequestDone:
		t.Fatal("winning request context was cancelled before body close")
	default:
	}

	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read winning body: %v", err)
	}
	if string(content) != "winner" {
		t.Fatalf("winning body = %q, want winner", content)
	}
	if err := response.Body.Close(); err != nil {
		t.Fatalf("close winning body: %v", err)
	}
	waitForSignal(t, winnerRequestDone, "winning request cancellation")
}

func TestRaceHTTPRequestsRealWinnerBodySurvivesParentCancellation(t *testing.T) {
	loserStarted := make(chan struct{})
	loserStopped := make(chan struct{})
	releaseWinnerBody := make(chan struct{})
	var releaseOnce sync.Once
	releaseWinner := func() { releaseOnce.Do(func() { close(releaseWinnerBody) }) }

	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/winner":
			<-loserStarted
			writer.WriteHeader(http.StatusOK)
			writer.(http.Flusher).Flush()
			<-releaseWinnerBody
			_, _ = io.WriteString(writer, "winner")
		case "/loser":
			close(loserStarted)
			<-request.Context().Done()
			close(loserStopped)
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()
	defer releaseWinner()

	ctx, cancel := context.WithCancel(context.Background())
	request := func(path string) requestFunc {
		return func(requestCtx context.Context) (*http.Response, error) {
			req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, server.URL+path, nil)
			if err != nil {
				return nil, err
			}
			return server.Client().Do(req)
		}
	}

	response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
		{label: "winner", request: request("/winner")},
		{label: "loser", request: request("/loser")},
	})
	if err != nil {
		cancel()
		t.Fatalf("request race failed: %v", err)
	}
	if response == nil {
		cancel()
		t.Fatal("request race returned no real response")
	}
	waitForSignal(t, loserStopped, "real losing request cancellation")

	cancel()
	releaseWinner()
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read real winning body after parent cancellation: %v", err)
	}
	if string(content) != "winner" {
		t.Fatalf("real winning body = %q, want winner", content)
	}
	if err := response.Body.Close(); err != nil {
		t.Fatalf("close real winning body: %v", err)
	}
}

func TestRaceHTTPRequestsFailureAndDeadlineAreRetained(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	workerErr := errors.New("worker failed")
	failureReturned := make(chan struct{})
	response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
		{
			label: "failed",
			request: func(context.Context) (*http.Response, error) {
				close(failureReturned)
				return nil, workerErr
			},
		},
		{
			label: "blocked",
			request: func(requestCtx context.Context) (*http.Response, error) {
				<-failureReturned
				<-requestCtx.Done()
				return nil, requestCtx.Err()
			},
		},
	})

	if response != nil {
		t.Fatal("failed request race returned a response")
	}
	if !errors.Is(err, workerErr) {
		t.Fatalf("request race error = %v, want worker failure", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("request race error = %v, want deadline exceeded", err)
	}
}

func TestRaceHTTPRequestsAllFailuresAreJoined(t *testing.T) {
	firstErr := errors.New("first failure")
	secondErr := errors.New("second failure")
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "first",
			request: func(context.Context) (*http.Response, error) {
				return nil, firstErr
			},
		},
		{
			label: "second",
			request: func(context.Context) (*http.Response, error) {
				return nil, secondErr
			},
		},
	})

	if response != nil {
		t.Fatal("failed request race returned a response")
	}
	if err == nil {
		t.Fatal("all-failed request race returned a nil error")
	}
	if !errors.Is(err, firstErr) || !errors.Is(err, secondErr) {
		t.Fatalf("joined error = %v, want both worker errors", err)
	}
}

func TestRaceHTTPRequestsRejectsNonOKAndClosesBody(t *testing.T) {
	body := newTrackingReadCloser("rejected")
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "http(s)",
			request: func(context.Context) (*http.Response, error) {
				return &http.Response{
					StatusCode: http.StatusTeapot,
					Status:     "418 I'm a teapot",
					Body:       body,
				}, nil
			},
		},
	})

	if response != nil {
		t.Fatal("non-200 request returned a response")
	}
	if err == nil || !strings.Contains(err.Error(), "rejected") {
		t.Fatalf("non-200 error = %v, want response body text", err)
	}
	assertBodyClosed(t, body)
}

func TestSocksCredentialsAndPort(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan error, 1)
	go func() {
		done <- func() error {
			conn, err := listener.Accept()
			if err != nil {
				return err
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
			header := make([]byte, 2)
			if _, err = io.ReadFull(conn, header); err != nil {
				return err
			}
			methods := make([]byte, int(header[1]))
			if _, err = io.ReadFull(conn, methods); err != nil {
				return err
			}
			if header[0] != 5 || !bytes.Contains(methods, []byte{2}) {
				return fmt.Errorf("missing SOCKS auth")
			}
			if _, err = conn.Write([]byte{5, 2}); err != nil {
				return err
			}
			if _, err = io.ReadFull(conn, header); err != nil {
				return err
			}
			username := make([]byte, int(header[1]))
			if _, err = io.ReadFull(conn, username); err != nil {
				return err
			}
			length := make([]byte, 1)
			if _, err = io.ReadFull(conn, length); err != nil {
				return err
			}
			password := make([]byte, int(length[0]))
			if _, err = io.ReadFull(conn, password); err != nil {
				return err
			}
			if string(username) != "User" || string(password) != "secret phrase" {
				return fmt.Errorf("wrong credentials")
			}
			if _, err = conn.Write([]byte{1, 0}); err != nil {
				return err
			}
			request := make([]byte, 4)
			if _, err = io.ReadFull(conn, request); err != nil {
				return err
			}
			var addressLength int
			switch request[3] {
			case 1:
				addressLength = 4
			case 4:
				addressLength = 16
			case 3:
				if _, err = io.ReadFull(conn, length); err != nil {
					return err
				}
				addressLength = int(length[0])
			default:
				return fmt.Errorf("unknown address type")
			}
			if _, err = io.ReadFull(conn, make([]byte, addressLength+2)); err != nil {
				return err
			}
			if _, err = conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
				return err
			}
			if _, err = http.ReadRequest(bufio.NewReader(conn)); err != nil {
				return err
			}
			_, err = io.WriteString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
			return err
		}()
	}()
	client := NewHttpClient()
	defer client.Close()
	client.TrySocks5(int32(listener.Addr().(*net.TCPAddr).Port), "User", "secret phrase")
	request := client.NewRequest()
	if err = request.SetURL("http://example.test/probe"); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	content, err := response.GetContent()
	if err != nil || string(content) != "ok" {
		t.Fatalf("content %q: %v", content, err)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
}

func TestRedirectStripsDeviceHeadersAndCredentials(t *testing.T) {
	destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for _, key := range []string{"X-Hwid", "X-Device-Os", "Authorization", "Cookie", "Proxy-Authorization"} {
			if r.Header.Get(key) != "" {
				t.Errorf("leaked %s", key)
			}
		}
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/second", http.StatusFound)
			return
		}
		_, _ = io.WriteString(w, "ok")
	}))
	defer destination.Close()
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, destination.URL, http.StatusFound) }))
	defer origin.Close()
	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	_ = request.SetURL(origin.URL)
	for _, key := range []string{"X-Hwid", "X-Device-Os", "Authorization", "Cookie", "Proxy-Authorization"} {
		request.SetHeader(key, "sensitive")
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	_, _ = response.GetContent()
}

func TestCustomHeadersStayOnOriginalOrigin(t *testing.T) {
	for _, h3Fallback := range []bool{false, true} {
		t.Run(fmt.Sprint(h3Fallback), func(t *testing.T) {
			destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Example-Api-Key") != "" {
					t.Error("custom credential crossed origin")
				}
				if r.URL.Path == "/" {
					http.Redirect(w, r, "/last", http.StatusFound)
					return
				}
				_, _ = io.WriteString(w, "ok")
			}))
			defer destination.Close()
			origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Example-Api-Key") != "fixture" {
					t.Error("same-origin credential lost")
				}
				if r.URL.Path == "/" {
					http.Redirect(w, r, "/same", http.StatusFound)
					return
				}
				http.Redirect(w, r, destination.URL, http.StatusFound)
			}))
			defer origin.Close()
			client := NewHttpClient()
			defer client.Close()
			if h3Fallback {
				client.TryH3Direct()
			}
			request := client.NewRequest()
			if err := request.SetURL(origin.URL); err != nil {
				t.Fatal(err)
			}
			request.SetHeader("Example-Api-Key", "fixture")
			response, err := request.Execute()
			if err != nil {
				t.Fatal(err)
			}
			body, err := response.GetContent()
			if err != nil || string(body) != "ok" {
				t.Fatalf("body=%q, error=%v", body, err)
			}
		})
	}
}

func TestRejectsHTTPSDowngradeAndRedirectLoop(t *testing.T) {
	via, _ := http.NewRequest("GET", "https://example.test", nil)
	next, _ := http.NewRequest("GET", "http://example.test", nil)
	if checkHTTPRedirect(next, []*http.Request{via}) == nil {
		t.Fatal("accepted downgrade")
	}
	via.URL.Scheme = "http"
	if checkHTTPRedirect(next, make([]*http.Request, 10)) == nil {
		t.Fatal("accepted redirect loop")
	}
}

func TestHTTPResponseLimit(t *testing.T) {
	response := &httpResponse{Response: &http.Response{Body: io.NopCloser(strings.NewReader(strings.Repeat("x", 16*1024*1024+1)))}}
	if _, err := response.GetContent(); err == nil {
		t.Fatal("accepted oversized response")
	}
}

func TestVerifiedTLSOnPinnedToolchain(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "ok") }))
	defer server.Close()
	client := NewHttpClient().(*httpClient)
	defer client.Close()
	client.tls = *server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	client.ModernTLS()
	request := client.NewRequest()
	_ = request.SetURL(server.URL)
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	_, err = response.GetContent()
	if err != nil {
		t.Fatal(err)
	}
}

func TestUTLSFingerprintsOnPinnedToolchain(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "ok") }))
	defer server.Close()
	for _, hello := range []utls.ClientHelloID{utls.HelloChrome_Auto, utls.HelloFirefox_Auto} {
		t.Run(hello.Client, func(t *testing.T) {
			conn, err := net.DialTimeout("tcp", server.Listener.Addr().String(), 5*time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
			secure := utls.UClient(conn, &utls.Config{ServerName: "127.0.0.1", RootCAs: server.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs}, hello)
			if err = secure.Handshake(); err != nil {
				t.Fatal(err)
			}
			if _, err = io.WriteString(secure, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"); err != nil {
				t.Fatal(err)
			}
			response, err := http.ReadResponse(bufio.NewReader(secure), nil)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != 200 {
				t.Fatal(response.Status)
			}
		})
	}
}

func TestInvalidAssetAndTruncatedXZPreserveCurrentFile(t *testing.T) {
	dir := t.TempDir()
	current := filepath.Join(dir, "geoip.db")
	staged := filepath.Join(dir, "staged")
	_ = os.WriteFile(current, []byte("original"), 0600)
	_ = os.WriteFile(staged, []byte("corrupted"), 0600)
	if InstallRouteAsset(staged, current) == nil {
		t.Fatal("accepted corrupt database")
	}
	var compressed bytes.Buffer
	writer, _ := xz.NewWriter(&compressed)
	_, _ = writer.Write([]byte("replacement"))
	_ = writer.Close()
	_ = os.WriteFile(staged, compressed.Bytes()[:compressed.Len()-4], 0600)
	if Unxz(staged, current) == nil {
		t.Fatal("accepted truncated XZ")
	}
	value, _ := os.ReadFile(current)
	if string(value) != "original" {
		t.Fatal("destroyed current asset")
	}
}

func TestZipRefusesTraversalAndSymlinks(t *testing.T) {
	for _, symlink := range []bool{false, true} {
		dir := t.TempDir()
		archive := filepath.Join(dir, "test.zip")
		file, _ := os.Create(archive)
		writer := zip.NewWriter(file)
		header := &zip.FileHeader{Name: "../outside"}
		if symlink {
			header.Name = "link"
			header.SetMode(os.ModeSymlink | 0600)
		}
		entry, _ := writer.CreateHeader(header)
		_, _ = entry.Write([]byte("outside"))
		_ = writer.Close()
		_ = file.Close()
		if Unzip(archive, filepath.Join(dir, "output")) == nil {
			t.Fatal("accepted unsafe ZIP")
		}
		if _, err := os.Stat(filepath.Join(dir, "outside")); !os.IsNotExist(err) {
			t.Fatal("wrote outside output")
		}
	}
}

func TestLogicalTunGuardInActualCore(t *testing.T) {
	var options option.Rule
	const config = `{"type":"logical","mode":"and","action":"reject","rules":[{"inbound":["tun-in"]},{"type":"logical","mode":"or","invert":true,"rules":[{"user_id":[12345,20001]},{"user_id":[1000,1051],"port":[53,853]}]}]}`
	if err := options.UnmarshalJSONContext(context.Background(), []byte(config)); err != nil {
		t.Fatal(err)
	}
	guard, err := rule.NewRule(context.Background(), log.NewNOPFactory().NewLogger("test"), options, false)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Close()
	for _, test := range []struct {
		name, inbound string
		uid           int32
		port          uint16
		reject        bool
	}{
		{"selected", "tun-in", 12345, 443, false}, {"owner", "tun-in", 20001, 443, false},
		{"unselected", "tun-in", 23456, 443, true},
		{"unselected DNS", "tun-in", 23456, 53, true}, {"unselected DoT", "tun-in", 23456, 853, true},
		{"work profile", "tun-in", 112345, 443, true}, {"work profile DNS", "tun-in", 112345, 53, true}, {"unknown DNS", "tun-in", -1, 53, true}, {"socks", "socks-in", 23456, 443, false}, {"http", "http-in", 23456, 443, false},
		{"dns", "tun-in", 1000, 53, false}, {"dot", "tun-in", 1051, 853, false}, {"system other", "tun-in", 1000, 443, true}, {"unknown", "tun-in", -1, 443, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			metadata := &adapter.InboundContext{Inbound: test.inbound, Destination: M.Socksaddr{Port: test.port}, ProcessInfo: &adapter.ConnectionOwner{UserId: test.uid}}
			if got := guard.Match(metadata); got != test.reject {
				t.Fatalf("reject=%v, want %v", got, test.reject)
			}
		})
	}
}

func TestProxyOnlyNeverFallsBackOnConnectionOrHandshakeFailure(t *testing.T) {
	for _, handshake := range []bool{false, true} {
		t.Run(fmt.Sprint(handshake), func(t *testing.T) {
			hits := make(chan struct{}, 1)
			direct := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits <- struct{}{} }))
			defer direct.Close()
			socks, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			port := int32(socks.Addr().(*net.TCPAddr).Port)
			if handshake {
				defer socks.Close()
				go func() {
					c, err := socks.Accept()
					if err == nil {
						defer c.Close()
						_, _ = c.Write([]byte{5, 255})
					}
				}()
			} else {
				_ = socks.Close()
			}
			client := NewHttpClient()
			defer client.Close()
			if err = client.RequireSocks5(port, "user", "password"); err != nil {
				t.Fatal(err)
			}
			client.TryH3Direct() // strict policy also overrides a later direct H3 request
			request := client.NewRequest()
			_ = request.SetURL(direct.URL)
			if _, err = request.Execute(); err == nil || !strings.Contains(err.Error(), "proxy-only download") {
				t.Fatalf("wrong error: %v", err)
			}
			select {
			case <-hits:
				t.Fatal("leaked direct request")
			default:
			}
		})
	}
}

func TestVirtualDNSDoesNotDialDoTThroughLAN(t *testing.T) {
	var options option.Rule
	const config = `{"inbound":["tun-in"],"ip_cidr":["172.19.0.2/32","fdfe:dcba:9876::2/128"],"port":[853],"action":"reject"}`
	if err := options.UnmarshalJSONContext(context.Background(), []byte(config)); err != nil {
		t.Fatal(err)
	}
	guard, err := rule.NewRule(context.Background(), log.NewNOPFactory().NewLogger("test"), options, false)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Close()
	for _, test := range []struct {
		inbound, destination string
		reject               bool
	}{
		{"tun-in", "172.19.0.2:853", true}, {"tun-in", "[fdfe:dcba:9876::2]:853", true},
		{"tun-in", "172.19.0.2:53", false}, {"tun-in", "8.8.8.8:853", false},
		{"tun-in", "[2001:4860:4860::8888]:853", false}, {"socks-in", "172.19.0.2:853", false},
	} {
		metadata := &adapter.InboundContext{Inbound: test.inbound, Destination: M.ParseSocksaddr(test.destination)}
		if got := guard.Match(metadata); got != test.reject {
			t.Fatalf("%s/%s reject=%v, want %v", test.inbound, test.destination, got, test.reject)
		}
	}
}
