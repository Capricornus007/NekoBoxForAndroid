package libcore

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
)

type testLocalDNS struct {
	lookup func(*ExchangeContext, string)
}

func (*testLocalDNS) Raw() bool            { return false }
func (*testLocalDNS) NetworkHandle() int64 { return 0 }
func (d *testLocalDNS) Lookup(ctx *ExchangeContext, network, domain string) error {
	d.lookup(ctx, network)
	return nil
}
func (*testLocalDNS) Exchange(*ExchangeContext, []byte) error {
	return errors.New("unexpected raw DNS call")
}

func TestLocalDNSCompletesSuccessfulLookup(t *testing.T) {
	for _, queryType := range []uint16{mDNS.TypeA, mDNS.TypeAAAA} {
		transport := newPlatformTransport(&testLocalDNS{lookup: func(ctx *ExchangeContext, network string) {
			if queryType == mDNS.TypeA && network != "ip4" || queryType == mDNS.TypeAAAA && network != "ip6" {
				t.Errorf("wrong query network: %s", network)
			}
			if queryType == mDNS.TypeA {
				ctx.Success("192.0.2.10")
			} else {
				ctx.Success("2001:db8::10")
			}
		}}, "dns-local", option.LocalDNSServerOptions{})
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		message := new(mDNS.Msg).SetQuestion("example.test.", queryType)
		response, err := transport.Exchange(ctx, message)
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		if len(response.Answer) != 1 || response.Answer[0].Header().Rrtype != queryType {
			t.Fatal("missing matching address")
		}
		if queryType == mDNS.TypeA && response.Answer[0].(*mDNS.A).A.String() != "192.0.2.10" {
			t.Fatal("wrong IPv4")
		}
		if queryType == mDNS.TypeAAAA && netip.MustParseAddr(response.Answer[0].(*mDNS.AAAA).AAAA.String()) != netip.MustParseAddr("2001:db8::10") {
			t.Fatal("wrong IPv6")
		}
	}
}

func TestLocalDNSFailureAndCancellation(t *testing.T) {
	transport := newPlatformTransport(&testLocalDNS{lookup: func(ctx *ExchangeContext, _ string) { ctx.ErrorCode(mDNS.RcodeNameError) }}, "dns-local", option.LocalDNSServerOptions{})
	message := new(mDNS.Msg).SetQuestion("missing.test.", mDNS.TypeA)
	if _, err := transport.Exchange(context.Background(), message); err == nil {
		t.Fatal("lost DNS error")
	}
	transport.iif = &testLocalDNS{lookup: func(*ExchangeContext, string) {}}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := transport.Exchange(ctx, message); err == nil {
		t.Fatal("ignored cancellation")
	}
	if _, err := transport.Exchange(context.Background(), new(mDNS.Msg)); err == nil {
		t.Fatal("accepted empty DNS question")
	}
}

type rawNetworkDNS struct{ testLocalDNS }

func (*rawNetworkDNS) Raw() bool            { return true }
func (*rawNetworkDNS) NetworkHandle() int64 { return 42 }

func TestPlatformRawDNSUsesUnderlyingNetworkHandle(t *testing.T) {
	previous := rawQueryFunc
	defer func() { rawQueryFunc = previous }()
	rawQueryFunc = func(networkHandle int64, payload []byte) ([]byte, error) {
		if networkHandle != 42 {
			t.Fatalf("DNS used network handle %d instead of underlying network", networkHandle)
		}
		request := new(mDNS.Msg)
		if err := request.Unpack(payload); err != nil {
			return nil, err
		}
		response := new(mDNS.Msg).SetReply(request)
		q := request.Question[0]
		if q.Qtype == mDNS.TypeA {
			response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: q.Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET}, A: net.ParseIP("192.0.2.10")}}
		} else {
			response.Answer = []mDNS.RR{&mDNS.AAAA{Hdr: mDNS.RR_Header{Name: q.Name, Rrtype: mDNS.TypeAAAA, Class: mDNS.ClassINET}, AAAA: net.ParseIP("2001:db8::10")}}
		}
		return response.Pack()
	}
	transport := newPlatformTransport(&rawNetworkDNS{}, "dns-direct", option.LocalDNSServerOptions{})
	for _, qtype := range []uint16{mDNS.TypeA, mDNS.TypeAAAA} {
		response, err := transport.Exchange(context.Background(), new(mDNS.Msg).SetQuestion("bootstrap.test.", qtype))
		if err != nil || len(response.Answer) != 1 || response.Answer[0].Header().Rrtype != qtype {
			t.Fatalf("raw DNS type %d failed: %v", qtype, err)
		}
	}
}
