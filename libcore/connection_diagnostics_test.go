package libcore

import (
	"bufio"
	"context"
	"fmt"
	"github.com/sagernet/sing-box/adapter"
	boxlog "github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestConnectionHistoryRequiresDebugAndIsBounded(t *testing.T) {
	for _, level := range []string{"warn", "info", "debug", "trace"} {
		instance, err := NewSingBoxInstance(fmt.Sprintf(`{"log":{"level":%q},"outbounds":[{"type":"direct","tag":"direct"}]}`, level), nil)
		if err != nil {
			t.Fatal(err)
		}
		enabled := level == "debug" || level == "trace"
		if (instance.diagnostics != nil) != enabled {
			t.Fatalf("history at %s", level)
		}
		_ = instance.Close()
	}
	instance, err := NewSingBoxInstance(`{"log":{"disabled":true,"level":"trace"},"outbounds":[{"type":"direct","tag":"direct"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	if instance.diagnostics != nil {
		t.Fatal("history captured with logging disabled")
	}
	_ = instance.Close()
	collector := &connectionDiagnostics{}
	for i := 0; i < 200; i++ {
		collector.GroupDial(context.Background(), fmt.Sprint(i))
	}
	snapshot := collector.snapshot()
	if len(snapshot) != 128 || snapshot[0]["outbound"] != "72" || snapshot[127]["outbound"] != "199" {
		t.Fatal("ring lost ordering or exceeded bound")
	}
}

type discardDiagnosticPlatformLog struct{}

func (discardDiagnosticPlatformLog) DisableColors() bool        { return true }
func (discardDiagnosticPlatformLog) WriteMessage(uint8, string) {}

func TestConnectionHistoryObservesActualRouteDNSAndGroupDial(t *testing.T) {
	previous := boxPlatformLogWriter
	boxPlatformLogWriter = discardDiagnosticPlatformLog{}
	defer func() { boxPlatformLogWriter = previous }()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok") }))
	defer server.Close()
	destination := M.SocksaddrFromNet(server.Listener.Addr())
	config := `{"log":{"level":"debug"},"dns":{"servers":[{"type":"local","tag":"test-dns"}]},"outbounds":[{"type":"direct","tag":"leaf"},{"type":"selector","tag":"proxy","outbounds":["leaf"]}],"route":{"final":"proxy","default_domain_resolver":{"server":"test-dns","strategy":"ipv4_only"}}}`
	instance, err := NewSingBoxInstance(config, &testLocalDNS{lookup: func(ctx *ExchangeContext, _ string) { ctx.Success("127.0.0.1") }})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	client, remote := net.Pipe()
	defer client.Close()
	defer remote.Close()
	_ = client.SetDeadline(time.Now().Add(2 * time.Second))
	metadata := adapter.InboundContext{Inbound: "test", InboundType: "socks", Source: M.ParseSocksaddr("127.0.0.1:32001"), Destination: M.Socksaddr{Fqdn: "history.test", Port: destination.Port}, ProcessInfo: &adapter.ConnectionOwner{UserId: 1212345, AndroidPackageNames: []string{"test.package"}}}
	ctx := boxlog.ContextWithNewID(instance.ctx)
	go instance.Router().RouteConnectionEx(ctx, remote, metadata, nil)
	if _, err = io.WriteString(client, "GET / HTTP/1.1\r\nHost: history.test\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(client), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || string(body) != "ok" {
		t.Fatalf("response %q: %v", body, err)
	}
	found := map[string]bool{}
	for _, event := range instance.diagnostics.snapshot() {
		if event["id"] == diagnosticID(ctx) && event["uid"] == int32(1212345) {
			found[event["kind"].(string)] = true
		}
	}
	for _, kind := range []string{"route", "dns", "selected"} {
		if !found[kind] {
			t.Fatalf("missing correlated %s event: %+v", kind, instance.diagnostics.snapshot())
		}
	}
}
