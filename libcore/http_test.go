package libcore

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
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

func TestCheckRedirectKeepsCustomHeadersOnlyOnTheSameHTTPSHost(t *testing.T) {
	request := &httpRequest{request: http.Request{Header: http.Header{}}}
	request.SetHeader("X-HWID", "device")
	first, _ := http.NewRequest(http.MethodGet, "https://sub.example/path", nil)

	cases := map[string]bool{
		"https://sub.example/next":      true,
		"https://SUB.example:443/next":  true,
		"https://sub.example:8443/next": false,
		"https://other.example/next":    false,
		"https://sub.example.net/next":  false,
		"https://a.sub.example/next":    false,
	}
	for target, kept := range cases {
		redirect, _ := http.NewRequest(http.MethodGet, target, nil)
		redirect.Header.Set("X-HWID", "device")
		redirect.Header.Set("Authorization", "Basic dXNlcjpwYXNz")
		redirect.Header.Set("Cookie", "session=1")
		redirect.Header.Set("Referer", "https://sub.example/path")
		redirect.Header.Set("User-Agent", "ua")
		if err := request.checkRedirect(redirect, []*http.Request{first}); err != nil {
			t.Fatalf("%s: %v", target, err)
		}
		for _, key := range []string{"X-HWID", "Authorization", "Cookie", "Referer"} {
			if got := redirect.Header.Get(key) != ""; got != kept {
				t.Fatalf("%s: %s kept = %v, want %v", target, key, got, kept)
			}
		}
		if redirect.Header.Get("User-Agent") != "ua" {
			t.Fatalf("%s: User-Agent was dropped", target)
		}
	}

	// Leaving HTTPS stops the chain, also after an HTTPS hop of a chain that began in the clear.
	for _, chain := range [][]string{
		{"https://sub.example/path", "http://sub.example/next"},
		{"https://sub.example/path", "https://other.example/next", "http://other.example/last"},
		{"http://plain.example/path", "https://sub.example/next", "http://sub.example/last"},
	} {
		var via []*http.Request
		for _, hop := range chain[:len(chain)-1] {
			previous, _ := http.NewRequest(http.MethodGet, hop, nil)
			via = append(via, previous)
		}
		redirect, _ := http.NewRequest(http.MethodGet, chain[len(chain)-1], nil)
		if err := request.checkRedirect(redirect, via); !errors.Is(err, errRedirectDowngrade) {
			t.Fatalf("%v: got %v, want a refused downgrade", chain, err)
		}
	}
	// Back on the first origin after another one, the credentials return but the Referer, the
	// intermediary's URL, does not.
	relay, _ := http.NewRequest(http.MethodGet, "https://relay.example/hop", nil)
	back, _ := http.NewRequest(http.MethodGet, "https://sub.example/final", nil)
	back.Header.Set("X-HWID", "device")
	back.Header.Set("Referer", "https://relay.example/hop")
	if err := request.checkRedirect(back, []*http.Request{first, relay}); err != nil {
		t.Fatal(err)
	}
	if back.Header.Get("Referer") != "" || back.Header.Get("X-HWID") == "" {
		t.Fatal("return hop kept the intermediary Referer or lost the device header")
	}

	plain, _ := http.NewRequest(http.MethodGet, "http://plain.example/path", nil)
	upgrade, _ := http.NewRequest(http.MethodGet, "http://plain.example/next", nil)
	if err := request.checkRedirect(upgrade, []*http.Request{plain}); err != nil {
		t.Fatalf("plain HTTP chain refused: %v", err)
	}

	via := make([]*http.Request, 10)
	for i := range via {
		via[i] = first
	}
	if err := request.checkRedirect(first, via); err == nil {
		t.Fatal("ten redirects did not stop the chain")
	}
}
