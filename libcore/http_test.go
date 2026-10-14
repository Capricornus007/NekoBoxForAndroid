package libcore

import (
	"archive/zip"
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	utls "github.com/metacubex/utls"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/process"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/ulikunitz/xz"
)

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
	if err := json.Unmarshal([]byte(config), &options); err != nil {
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
		{"unselected", "tun-in", 23456, 443, true}, {"socks", "socks-in", 23456, 443, false}, {"http", "http-in", 23456, 443, false},
		{"dns", "tun-in", 1000, 53, false}, {"dot", "tun-in", 1051, 853, false}, {"system other", "tun-in", 1000, 443, true}, {"unknown", "tun-in", -1, 443, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			metadata := &adapter.InboundContext{Inbound: test.inbound, Destination: M.Socksaddr{Port: test.port}, ProcessInfo: &process.Info{UserId: test.uid}}
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
	if err := json.Unmarshal([]byte(config), &options); err != nil {
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
