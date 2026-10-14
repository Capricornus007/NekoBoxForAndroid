package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/ech"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32, username, password string)
	RequireSocks5(port int32, username, password string) error
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
	requireSocks5 bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	client.h1h2Transport.DialContext = (&net.Dialer{Timeout: 15 * time.Second}).DialContext
	client.h1h2Transport.TLSHandshakeTimeout = 15 * time.Second
	client.h1h2Transport.ResponseHeaderTimeout = 30 * time.Second
	client.h1h2Client.Timeout = 3 * time.Minute
	client.h1h2Client.CheckRedirect = checkHTTPRedirect
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32, username, password string) {
	dialer := &net.Dialer{Timeout: 15 * time.Second}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if socksConn != nil {
					_ = socksConn.Close()
				}
				if c.requireSocks5 {
					return nil, fmt.Errorf("proxy-only download: SOCKS5 unavailable: %w", err)
				}
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			deadline := time.Now().Add(15 * time.Second)
			if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
				deadline = limit
			}
			_ = socksConn.SetDeadline(deadline)
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), username, password)
			if err != nil {
				if socksConn != nil {
					_ = socksConn.Close()
				}
				if c.requireSocks5 {
					return nil, fmt.Errorf("proxy-only download: SOCKS5 handshake failed: %w", err)
				}
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_ = socksConn.SetDeadline(time.Time{})
			return socksConn, nil
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) RequireSocks5(port int32, username, password string) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("invalid SOCKS5 port")
	}
	c.requireSocks5 = true
	c.TrySocks5(port, username, password)
	return nil
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request       http.Request
	customHeaders []string
}

type customHTTPHeadersKey struct{}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if (r.request.URL.Scheme != "http" && r.request.URL.Scheme != "https") || r.request.URL.Hostname() == "" {
		return errors.New("expected an HTTP(S) URL with a host")
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
	r.customHeaders = append(r.customHeaders, key)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
	body := append([]byte(nil), content...)
	r.request.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(body)), nil }
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (result HTTPResponse, err error) {
	defer func() {
		if value := recover(); value != nil {
			result = nil
			err = fmt.Errorf("http execute: %v", value)
		}
	}()
	r.request = *r.request.WithContext(context.WithValue(r.request.Context(), customHTTPHeadersKey{}, append([]string(nil), r.customHeaders...)))
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	response, err := r.h1h2Client.Do(&r.request)
	if err != nil {
		// trySocks5 && tryH3Direct
		if !r.requireSocks5 && r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		defer response.Body.Close()
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

// Device headers and credentials must never follow a cross-origin redirect.
func checkHTTPRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return errors.New("too many redirects")
	}
	previous := via[len(via)-1].URL
	if previous.Scheme == "https" && req.URL.Scheme != "https" {
		return errors.New("refusing an HTTPS downgrade")
	}
	// net/http rebuilds redirect headers from the initial request on every hop.
	// Once the chain leaves that origin, keep stripping them on subsequent hops.
	original := via[0].URL
	crossOrigin := httpOrigin(req.URL) != httpOrigin(original)
	for _, hop := range via {
		crossOrigin = crossOrigin || httpOrigin(hop.URL) != httpOrigin(original)
	}
	if crossOrigin {
		if custom, ok := via[0].Context().Value(customHTTPHeadersKey{}).([]string); ok {
			for _, key := range custom {
				req.Header.Del(key)
			}
		}
		for key := range req.Header {
			if strings.HasPrefix(strings.ToLower(key), "x-") {
				req.Header.Del(key)
			}
		}
		for _, key := range []string{"Authorization", "Cookie", "Proxy-Authorization"} {
			req.Header.Del(key)
		}
	}
	return nil
}

func httpOrigin(u *url.URL) string {
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	return strings.ToLower(u.Scheme) + "://" + net.JoinHostPort(strings.ToLower(u.Hostname()), port)
}

type cleanupBody struct {
	io.ReadCloser
	cleanup func()
	once    sync.Once
}

func (b *cleanupBody) Close() error {
	err := b.ReadCloser.Close()
	b.once.Do(b.cleanup)
	return err
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	ctx, cancel := context.WithTimeout(r.request.Context(), 3*time.Minute)
	transports := []http.RoundTripper{
		&http.Transport{
			DialContext: (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
			DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				conn, err := (&net.Dialer{Timeout: 15 * time.Second}).DialContext(ctx, network, addr)
				if err != nil {
					return nil, err
				}
				host, _, err := net.SplitHostPort(addr)
				if err != nil {
					_ = conn.Close()
					return nil, err
				}
				tlsCtx, tlsCancel := context.WithTimeout(ctx, 15*time.Second)
				defer tlsCancel()
				secure, err := ech.NewECHClientConfig(host, &r.tls, gLocalDNSTransport).Client(tlsCtx, conn)
				if err != nil {
					_ = conn.Close()
				}
				return secure, err
			},
			DisableKeepAlives:     true,
			ResponseHeaderTimeout: 30 * time.Second,
		},
	}
	if r.request.URL.Scheme == "https" {
		transports = append(transports, &http3.Transport{
			TLSClientConfig: r.tls.Clone(),
			QUICConfig:      &quic.Config{HandshakeIdleTimeout: 15 * time.Second, MaxIdleTimeout: 30 * time.Second},
		})
	}
	type outcome struct {
		index    int
		response *http.Response
		err      error
	}
	results := make(chan outcome)
	cancels := make([]context.CancelFunc, len(transports))
	cleanups := make([]func(), len(transports))
	for i, transport := range transports {
		attemptCtx, attemptCancel := context.WithCancel(ctx)
		cancels[i] = attemptCancel
		cleanups[i] = func() {
			attemptCancel()
			if closer, ok := transport.(io.Closer); ok {
				_ = closer.Close()
			}
			if closer, ok := transport.(interface{ CloseIdleConnections() }); ok {
				closer.CloseIdleConnections()
			}
		}
		go func(i int, transport http.RoundTripper, attemptCtx context.Context) {
			request := r.request.Clone(attemptCtx)
			var response *http.Response
			var err error
			func() {
				defer func() {
					if value := recover(); value != nil {
						err = fmt.Errorf("http request: %v", value)
					}
				}()
				if request.GetBody != nil {
					request.Body, err = request.GetBody()
					if err != nil {
						return
					}
				}
				response, err = (&http.Client{Transport: transport, Timeout: 3 * time.Minute, CheckRedirect: checkHTTPRedirect}).Do(request)
				if err == nil && response.StatusCode != http.StatusOK {
					err = errors.New((&httpResponse{Response: response}).errorString())
				}
			}()
			if err != nil && response != nil {
				_ = response.Body.Close()
				response = nil
			}
			select {
			case results <- outcome{i, response, err}:
			case <-attemptCtx.Done():
				if response != nil {
					_ = response.Body.Close()
				}
			}
		}(i, transport, attemptCtx)
	}
	closeAll := func() {
		cancel()
		for _, cleanup := range cleanups {
			cleanup()
		}
	}
	var finalErr error
	for range transports {
		select {
		case result := <-results:
			if result.err == nil && result.response != nil {
				for i, cleanup := range cleanups {
					if i != result.index {
						cleanup()
					}
				}
				result.response.Body = &cleanupBody{ReadCloser: result.response.Body, cleanup: closeAll}
				return &httpResponse{Response: result.response}, nil
			}
			finalErr = errors.Join(finalErr, result.err)
		case <-ctx.Done():
			closeAll()
			return nil, errors.Join(finalErr, ctx.Err())
		}
	}
	closeAll()
	return nil, finalErr
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, 16*1024*1024+1))
		if len(h.content) > 16*1024*1024 {
			h.content = nil
			h.contentError = errors.New("HTTP response exceeds 16 MiB")
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	n, err := io.Copy(file, io.LimitReader(h.Body, 512*1024*1024+1))
	if n > 512*1024*1024 {
		return errors.New("HTTP download exceeds 512 MiB")
	}
	if err != nil {
		return err
	}
	return file.Close()
}
