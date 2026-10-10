package libcore

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHTTPTimeoutBounds(t *testing.T) {
	client := NewHttpClient()
	defer client.Close()
	for _, timeout := range []int32{0, -1, 600001} {
		if err := client.SetTimeoutMs(timeout); err == nil {
			t.Fatal("invalid timeout accepted")
		}
	}
	if err := client.SetTimeoutMs(20000); err != nil {
		t.Fatal(err)
	}
}

func TestHTTPTimeoutBoundsStalledBody(t *testing.T) {
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer server.Close()
	defer close(release)

	client := NewHttpClient()
	defer client.Close()
	if err := client.SetTimeoutMs(200); err != nil {
		t.Fatal(err)
	}
	request := client.NewRequest()
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	response, err := request.Execute()
	if err == nil {
		_, err = response.GetContentLimited(1024)
	}
	if err == nil {
		t.Fatal("stalled body read without error")
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("timeout not applied, took %v", elapsed)
	}
}
