package libcore

import (
	"fmt"
	"net"
	"strings"
	"testing"
)

func TestConfigValidationDoesNotStartListenersOrReplaceActiveInstance(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	original := mainInstance
	sentinel := &BoxInstance{}
	mainInstance = sentinel
	defer func() { mainInstance = original }()
	port := listener.Addr().(*net.TCPAddr).Port
	config := fmt.Sprintf(`{"inbounds":[{"type":"socks","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`, port)
	if err := CheckConfig(config, nil); err != nil {
		t.Fatal(err)
	}
	validGroup := `{"outbounds":[{"type":"direct","tag":"leaf"},{"type":"urltest","tag":"group","outbounds":["leaf"],"strategy":"priority","wait_for_initial":true}],"route":{"final":"group"}}`
	if err := CheckConfig(validGroup, nil); err != nil {
		t.Fatal(err)
	}
	for _, addresses := range []string{`["172.19.0.1/28"]`, `["fdfe:dcba:9876::1/126"]`, `["172.19.0.1/28","fdfe:dcba:9876::1/126"]`} {
		config := fmt.Sprintf(`{"inbounds":[{"type":"tun","tag":"tun-in","address":%s,"auto_route":true}],"outbounds":[{"type":"direct","tag":"direct"}]}`, addresses)
		if err := CheckConfig(config, nil); err != nil {
			t.Fatal(err)
		}
	}
	if mainInstance != sentinel {
		t.Fatal("changed the running instance")
	}
	invalid := `{"dns":{"servers":[{"type":"local","tag":"user-dns"}]},"outbounds":[{"type":"http","server":"proxy.example","server_port":443,"domain_resolver":"missing-dns"}]}`
	err = CheckConfig(invalid, nil)
	if err == nil || !strings.Contains(err.Error(), "domain resolver not found") {
		t.Fatalf("invalid resolver accepted: %v", err)
	}
	for _, config := range []string{
		`{"outbounds":[{"type":"selector","tag":"group","outbounds":["missing"]}]}`,
		`{"outbounds":[{"type":"selector","tag":"first","outbounds":["second"]},{"type":"selector","tag":"second","outbounds":["first"]}]}`,
	} {
		if err := CheckConfig(config, nil); err == nil {
			t.Fatalf("invalid graph accepted: %s", config)
		}
	}
}
