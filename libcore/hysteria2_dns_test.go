package libcore

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	boxtls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-quic/hysteria2"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type hysteriaDNSForwarder struct{ juicityEcho }

func (hysteriaDNSForwarder) NewConnectionEx(ctx context.Context, conn net.Conn, _, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		defer conn.Close()
		upstream, err := (&net.Dialer{}).DialContext(ctx, "tcp", destination.String())
		if err == nil {
			defer upstream.Close()
			go func() { _, _ = io.Copy(upstream, conn) }()
			_, err = io.Copy(conn, upstream)
		}
		if onClose != nil {
			onClose(err)
		}
	}()
}

// Exercise the actual typed DoH -> Hysteria2 -> TLS path, including shared
// QUIC sessions, parallel DNS queries and recovery after a cancelled request.
func TestHysteria2LiveDNSOverTLSAndCancellation(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	logger := log.NewNOPFactory().NewLogger("hysteria-dns-test")
	cert, err := boxtls.GenerateKeyPair(nil, nil, time.Now, "fixture.test")
	if err != nil {
		t.Fatal(err)
	}
	key, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	certificate := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}))
	tlsServer, err := boxtls.NewSTDServer(ctx, logger, option.InboundTLSOptions{Enabled: true, ALPN: []string{"h3"}, Certificate: []string{certificate}, Key: []string{string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))}})
	if err != nil {
		t.Fatal(err)
	}
	defer tlsServer.Close()
	server, err := hysteria2.NewService[int](hysteria2.ServiceOptions{Context: ctx, Logger: logger, TLSConfig: tlsServer, Handler: hysteriaDNSForwarder{}})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	server.UpdateUsers([]int{1}, []string{"fixture-password"})
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	if err = server.Start(socket); err != nil {
		t.Fatal(err)
	}
	doh := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			return
		}
		request := new(mDNS.Msg)
		if err = request.Unpack(body); err != nil {
			t.Error(err)
			return
		}
		if request.Question[0].Name == "slow.example.test." {
			<-r.Context().Done()
			return
		}
		response := new(mDNS.Msg).SetReply(request)
		response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.ParseIP("192.0.2.10")}}
		wire, _ := response.Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(wire)
	}))
	defer doh.Close()
	configuration := map[string]any{
		"dns":       map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote", "server": "127.0.0.1", "server_port": doh.Listener.Addr().(*net.TCPAddr).Port, "detour": "proxy", "tls": map[string]any{"enabled": true, "insecure": true}}}, "final": "dns-remote"},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}, map[string]any{"type": "hysteria2", "tag": "proxy", "server": "127.0.0.1", "server_port": socket.LocalAddr().(*net.UDPAddr).Port, "password": "fixture-password", "tls": map[string]any{"enabled": true, "server_name": "fixture.test", "certificate": []string{certificate}}}},
		"route":     map[string]any{"final": "direct"},
	}
	content, _ := json.Marshal(configuration)
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, &dnsTestPlatform{})
	var options option.Options
	if err = decodeConfigOptions(ctx, content, &options); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	query := func(domain string, timeout time.Duration) error {
		requestCtx, stop := context.WithTimeout(ctx, timeout)
		defer stop()
		response, err := service.FromContext[adapter.DNSRouter](ctx).Exchange(requestCtx, new(mDNS.Msg).SetQuestion(domain, mDNS.TypeA), adapter.DNSQueryOptions{})
		if err != nil {
			return err
		}
		if len(response.Answer) != 1 || response.Answer[0].(*mDNS.A).A.String() != "192.0.2.10" {
			return fmt.Errorf("unexpected DNS answer: %v", response)
		}
		return nil
	}
	if err = query("initial.example.test.", 3*time.Second); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for index := range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := query(fmt.Sprintf("parallel-%d.example.test.", index), 3*time.Second); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if err = query("slow.example.test.", 100*time.Millisecond); err == nil {
		t.Fatal("slow DNS query did not time out")
	}
	if err = query("recovered.example.test.", 3*time.Second); err != nil {
		t.Fatal("cancelled DNS query broke Hysteria2 session: ", err)
	}
}
