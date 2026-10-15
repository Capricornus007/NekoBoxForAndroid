package libcore

import (
	"context"
	"encoding/json"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"io"
	"net"
	"reflect"
	"strings"
	"testing"
	"time"
)

func normalizedConfig(t *testing.T, raw string) map[string]any {
	t.Helper()
	value, err := normalizeConfigJSON(context.Background(), []byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := json.Unmarshal(value, &root); err != nil {
		t.Fatal(err)
	}
	return root
}

func TestLegacyInboundMigrationKeepsGuardsBeforeDNSAndSniffing(t *testing.T) {
	const raw = `{"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/28"],"sniff":true,"sniff_override_destination":true,"sniff_timeout":"500ms","domain_strategy":"prefer_ipv4","endpoint_independent_nat":true}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"inbound":["tun-in"],"user_id":[23456],"action":"reject"},{"inbound":["tun-in"],"port":[853],"ip_cidr":["172.19.0.2/32"],"action":"reject"},{"port":[53],"action":"hijack-dns"}],"final":"direct"}}`
	root := normalizedConfig(t, raw)
	inbound := root["inbounds"].([]any)[0].(map[string]any)
	if inbound["sniff"] != nil || inbound["domain_strategy"] != nil || inbound["endpoint_independent_nat"] != nil {
		t.Fatal("legacy fields survived")
	}
	if inbound["udp_mapping"] != "endpoint_independent" {
		t.Fatal("changed UDP mapping")
	}
	rules := root["route"].(map[string]any)["rules"].([]any)
	for i, action := range []string{"reject", "reject", "sniff", "resolve", "hijack-dns"} {
		if rules[i].(map[string]any)["action"] != action {
			t.Fatalf("changed guard order at %d", i)
		}
	}
	if rules[2].(map[string]any)["override_destination"] != true {
		t.Fatal("lost destination override")
	}
	if err := CheckConfig(raw, nil); err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(root)
	if again := normalizedConfig(t, string(encoded)); !reflect.DeepEqual(root, again) {
		t.Fatal("migration must be idempotent")
	}
}

func TestLegacyMigrationPreservesUserFieldsAndRejectsMalformedInput(t *testing.T) {
	const raw = `{"opaque":{"number":9007199254740993,"password":"test-fixture"},"inbounds":[{"type":"socks","tag":"neko-legacy-inbound-1"},{"type":"socks","sniff":true}],"route":{"rules":[{"action":"reject","domain":["blocked.example"]}]}}`
	result, err := normalizeConfigJSON(context.Background(), []byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(result), "9007199254740993") {
		t.Fatal("changed an exact numeric value")
	}
	root := normalizedConfig(t, string(result))
	items := root["inbounds"].([]any)
	if items[0].(map[string]any)["tag"] == items[1].(map[string]any)["tag"] {
		t.Fatal("generated a duplicate tag")
	}
	if root["opaque"].(map[string]any)["password"] != "test-fixture" {
		t.Fatal("modified unrelated user options")
	}
	for _, raw := range []string{`{} {}`, `null`, `{"inbounds":[{"sniff":"yes"}]}`, `{"inbounds":[{"sniff_timeout":false}]}`, `{"inbounds":[{"sniff":true}],"route":{"rules":{}}}`} {
		if _, err := normalizeConfigJSON(context.Background(), []byte(raw)); err == nil {
			t.Fatalf("accepted malformed config: %s", raw)
		}
	}
	if err := CheckConfig(`{"unknown_option":true}`, nil); err == nil {
		t.Fatal("migration must retain strict core validation")
	}
}

func TestLegacyDNSCompatibilityDoesNotRewriteModernRules(t *testing.T) {
	legacy := normalizedConfig(t, `{"dns":{"rules":[{"query_type":["A","AAAA"],"strategy":"ipv4_only","server":"fake"}]}}`)
	rule := legacy["dns"].(map[string]any)["rules"].([]any)[0].(map[string]any)
	if rule["query_type"] != nil || rule["neko_legacy_query_type"] == nil || rule["strategy"] != "ipv4_only" {
		t.Fatal("lost legacy DNS family/query behavior")
	}
	modern := normalizedConfig(t, `{"dns":{"rules":[{"query_type":["A"],"ip_version":4,"server":"modern"}]}}`)
	rule = modern["dns"].(map[string]any)["rules"].([]any)[0].(map[string]any)
	if rule["query_type"] == nil || rule["neko_legacy_query_type"] != nil {
		t.Fatal("changed native 1.14 DNS semantics")
	}
}

// Legacy sniff_override_destination must feed the following resolve action;
// otherwise GeoIP/IP rules see the original address instead of the sniffed host.
func TestLegacySniffedDestinationIsResolvedBeforeIPRouting(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			defer conn.Close()
			io.Copy(conn, conn)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(&testLocalDNS{lookup: func(exchange *ExchangeContext, _ string) { exchange.Success("127.0.0.1") }}), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, &dnsTestPlatform{})
	var options option.Options
	raw := `{"dns":{"servers":[{"type":"local","tag":"local"}],"final":"local"},"inbounds":[{"type":"socks","listen":"127.0.0.1","tag":"fixture-in","sniff":true,"sniff_override_destination":true,"domain_strategy":"ipv4_only"}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"ip_cidr":["127.0.0.1/32"],"outbound":"direct"},{"action":"reject"}]}}`
	if err = decodeConfigOptions(ctx, []byte(raw), &options); err != nil {
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
	client, input := net.Pipe()
	defer client.Close()
	defer input.Close()
	client.SetDeadline(time.Now().Add(2 * time.Second))
	router := service.FromContext[adapter.Router](ctx)
	metadata := adapter.InboundContext{Inbound: "fixture-in", Network: "tcp", Source: M.ParseSocksaddr("192.0.2.10:12345"), Destination: M.ParseSocksaddrHostPort("203.0.113.99", M.SocksaddrFromNet(listener.Addr()).Port)}
	go router.RouteConnectionEx(ctx, input, metadata, nil)
	payload := "GET /fixture HTTP/1.1\r\nHost: sniff.test\r\n\r\n"
	if _, err = io.WriteString(client, payload); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, len(payload))
	if _, err = io.ReadFull(client, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != payload {
		t.Fatal("sniff/resolve routing changed the payload")
	}
}
