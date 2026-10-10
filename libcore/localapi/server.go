// Package localapi provides the loopback transport for app control commands.
package localapi

import (
	"bytes"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"golang.org/x/net/netutil"
)

const MaxRequestBytes = 2 << 20
const MaxResponseBytes = 16 << 20

var tokenPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var operationPattern = regexp.MustCompile(`^[a-z]+\.[a-z]+$`)

type Handler interface {
	Call(operation, parameters string) string
}

type Server struct {
	http     *http.Server
	listener net.Listener
	done     chan struct{}
	close    sync.Once
}

func Start(port int, token string, handler Handler) (*Server, error) {
	if port < 0 || port > 65535 || !tokenPattern.MatchString(token) || handler == nil {
		return nil, errors.New("invalid local API configuration")
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, errors.New("local API port is unavailable")
	}
	listener = netutil.LimitListener(listener, 32)
	server := &Server{listener: listener, done: make(chan struct{})}
	server.http = &http.Server{
		Handler:           newHandler(token, handler),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      2 * time.Minute,
		IdleTimeout:       15 * time.Second,
		MaxHeaderBytes:    8192,
		// Panic reports must not include app payloads or credentials.
		ErrorLog: log.New(io.Discard, "", 0),
	}
	go func() {
		defer close(server.done)
		_ = server.http.Serve(listener)
	}()
	return server, nil
}

func (s *Server) Port() int {
	return s.listener.Addr().(*net.TCPAddr).Port
}

func (s *Server) Close() error {
	var err error
	s.close.Do(func() {
		err = s.http.Close()
		<-s.done
	})
	return err
}

// Android's object decoder has a smaller stack than encoding/json's nesting limit.
func boundedJSON(body []byte) bool {
	decoder := json.NewDecoder(bytes.NewReader(body))
	depth := 0
	for tokens := 0; tokens < 100000; tokens++ {
		token, err := decoder.Token()
		if err == io.EOF {
			return true
		}
		if err != nil {
			return false
		}
		if delimiter, ok := token.(json.Delim); ok {
			switch delimiter {
			case '{', '[':
				depth++
				if depth > 64 {
					return false
				}
			case '}', ']':
				depth--
			}
		}
	}
	return false
}

func newHandler(token string, handler Handler) http.Handler {
	busy := make(chan struct{}, 1)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		fail := func(status int, code string) {
			w.WriteHeader(status)
			_, _ = io.WriteString(w, `{"error":{"code":"`+code+`"}}`)
		}
		host, port, err := net.SplitHostPort(r.Host)
		portNumber, portErr := strconv.Atoi(port)
		if err != nil || portErr != nil || portNumber < 1 || portNumber > 65535 || host != "127.0.0.1" {
			fail(http.StatusForbidden, "invalid_host")
			return
		}
		if len(r.Header.Values("Origin")) > 0 || len(r.Header.Values("Sec-Fetch-Site")) > 0 {
			fail(http.StatusForbidden, "browser_request_refused")
			return
		}
		auth := r.Header.Values("Authorization")
		if len(auth) != 1 || subtle.ConstantTimeCompare([]byte(auth[0]), []byte("Bearer "+token)) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			fail(http.StatusUnauthorized, "unauthorized")
			return
		}
		if r.URL.RawQuery != "" || r.URL.RawPath != "" {
			fail(http.StatusBadRequest, "invalid_path")
			return
		}
		operation, body := "api.describe", []byte("{}")
		if r.Method == http.MethodGet && r.URL.Path == "/v1" {
			if r.ContentLength != 0 || len(r.TransferEncoding) != 0 {
				fail(http.StatusBadRequest, "unexpected_body")
				return
			}
		} else if r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/v1/") {
			operation = strings.TrimPrefix(r.URL.Path, "/v1/")
			if !operationPattern.MatchString(operation) {
				fail(http.StatusNotFound, "unknown_operation")
				return
			}
			mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if err != nil || mediaType != "application/json" || r.Header.Get("Content-Encoding") != "" {
				fail(http.StatusUnsupportedMediaType, "json_required")
				return
			}
			body, err = io.ReadAll(http.MaxBytesReader(w, r.Body, MaxRequestBytes))
			if err != nil {
				var tooLarge *http.MaxBytesError
				if errors.As(err, &tooLarge) {
					fail(http.StatusRequestEntityTooLarge, "request_too_large")
				} else {
					fail(http.StatusBadRequest, "unreadable_body")
				}
				return
			}
			trimmed := strings.TrimSpace(string(body))
			if !utf8.Valid(body) || !json.Valid(body) || !strings.HasPrefix(trimmed, "{") || !boundedJSON(body) {
				fail(http.StatusBadRequest, "object_required")
				return
			}
		} else {
			fail(http.StatusNotFound, "unknown_route")
			return
		}
		select {
		case busy <- struct{}{}:
			defer func() { <-busy }()
		default:
			w.Header().Set("Retry-After", "1")
			fail(http.StatusServiceUnavailable, "busy")
			return
		}
		defer func() {
			if recover() != nil {
				fail(http.StatusInternalServerError, "internal_error")
			}
		}()
		response := handler.Call(operation, string(body))
		if len(response) > MaxResponseBytes {
			fail(http.StatusInternalServerError, "response_too_large")
			return
		}
		if !json.Valid([]byte(response)) {
			fail(http.StatusInternalServerError, "invalid_response")
			return
		}
		_, _ = io.WriteString(w, response)
	})
}
