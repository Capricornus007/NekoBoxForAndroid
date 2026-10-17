package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tunin "github.com/sagernet/sing-box/protocol/tun"
	tun "github.com/sagernet/sing-tun"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

type tunAccessTestPlatform struct {
	boxPlatformInterfaceWrapper
	uid   int32
	err   error
	calls int
}

func (p *tunAccessTestPlatform) FindConnectionOwner(*adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	p.calls++
	if p.err != nil {
		return nil, p.err
	}
	return &adapter.ConnectionOwner{UserId: p.uid}, nil
}

func TestAndroidTunOwnerBeforeRoutingAndVirtualDNS(t *testing.T) {
	for _, mode := range []string{"allow", "bypass"} {
		t.Run(mode, func(t *testing.T) {
			guard, err := os.ReadFile("testdata/tun_" + mode + "_guard.json")
			if err != nil {
				t.Fatal(err)
			}
			raw := fmt.Sprintf(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"proxy"}],"route":{"final":"proxy","rules":[%s,{"inbound":["tun-in"],"port":[853],"ip_cidr":["172.19.0.2/32","fdfe:dcba:9876::2/128"],"action":"reject"},{"port":[53],"action":"hijack-dns"}]}}`, guard)
			ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
			ctx = service.ContextWithDefaultRegistry(ctx)
			platform := &tunAccessTestPlatform{}
			service.MustRegister[adapter.PlatformInterface](ctx, platform)
			var options option.Options
			if err = decodeConfigOptions(ctx, []byte(raw), &options); err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = instance.Close() })
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			// Run the actual TUN classifier without opening an Android device.
			var tunOptions option.TunInboundOptions
			if err = json.Unmarshal([]byte(`{"address":["172.19.0.1/28","fdfe:dcba:9876::1/126"],"dns_mode":"disabled"}`), &tunOptions); err != nil {
				t.Fatal(err)
			}
			inbound, err := tunin.NewInbound(ctx, instance.Router(), log.NewNOPFactory().NewLogger("tun-test"), "tun-in", tunOptions)
			if err != nil {
				t.Fatal(err)
			}
			if err = inbound.Start(adapter.StartStateInitialize, nil); err != nil {
				t.Fatal(err)
			}
			classifier := inbound.(interface {
				JudgeFlow(uint8, netip.AddrPort, netip.AddrPort, []byte) tun.FlowVerdict
			})
			for _, addresses := range [][2]string{{"172.19.0.1", "172.19.0.2"}, {"fdfe:dcba:9876::1", "fdfe:dcba:9876::2"}} {
				for _, test := range []struct {
					name              string
					uid               int32
					port              uint16
					lookupErr, reject bool
				}{
					{"selected", 12345, 443, false, false}, {"owner", 20001, 443, false, false},
					{"excluded", 23456, 443, false, true}, {"work profile copy", 1212345, 443, false, true},
					{"unknown", -1, 443, false, true}, {"lookup failure", 0, 443, true, true},
					{"selected DNS", 12345, 53, false, false}, {"excluded DNS", 23456, 53, false, true},
					{"work profile DNS", 1212345, 53, false, true}, {"unknown DNS", -1, 53, false, true},
					{"resolver DNS", 1051, 53, false, false}, {"resolver HTTPS", 1051, 443, false, true},
					{"system DNS", 1000, 53, false, mode == "bypass"}, {"virtual DoT", 12345, 853, false, true},
				} {
					t.Run(addresses[0]+"/"+test.name, func(t *testing.T) {
						platform.uid, platform.err = test.uid, nil
						if test.lookupErr {
							platform.err = errors.New("owner unavailable")
						}
						for _, protocol := range []uint8{6, 17} {
							before := platform.calls
							// Reuse tuples across UIDs; cached prior authorization is unsafe.
							verdict := classifier.JudgeFlow(protocol, netip.AddrPortFrom(netip.MustParseAddr(addresses[0]), 32000), netip.AddrPortFrom(netip.MustParseAddr(addresses[1]), test.port), nil)
							if platform.calls != before+1 {
								t.Fatalf("owner lookup skipped or cached for protocol %d", protocol)
							}
							rejected := verdict.Action == tun.ActionReject || verdict.Action == tun.ActionDrop
							if rejected != test.reject {
								t.Fatalf("protocol %d: action=%v reject=%v, want %v", protocol, verdict.Action, rejected, test.reject)
							}
							if !test.reject && protocol == 17 && test.port == 53 && verdict.Action != tun.ActionHijackDNS {
								t.Fatalf("allowed DNS lost interception: %v", verdict.Action)
							}
						}
					})
				}
			}
			before := platform.calls
			result := instance.Router().PreMatch(adapter.InboundContext{Inbound: "socks-in", InboundType: "socks", Network: "tcp", Source: M.ParseSocksaddr("192.0.2.9:32001"), Destination: M.ParseSocksaddr("192.0.2.10:443")}, nil)
			if platform.calls != before || result.Action == adapter.PreMatchReject {
				t.Fatal("changed non-TUN remote client handling")
			}
		})
	}
}
