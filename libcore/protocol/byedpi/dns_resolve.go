package byedpi

import (
	"context"
	"net/netip"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
)

// sing-box 上游在 17c52d03b 刪掉 adapter.DNSQueryOptionsFrom（它唯一的呼叫者改成
// 從 dialer 讀回 QueryOptions）。byedpi 的 domain_resolver 選項依賴這個行為，而
// byedpi 是我方私有出口、上游不會有它，所以在本包保留一份等價實作，而不是把已被
// 上游移除的公開 API 再塞回 sing-box fork（那樣每次同步上游都會重新衝突一次）。
func dnsQueryOptionsFrom(ctx context.Context, options *option.DomainResolveOptions) (adapter.DNSQueryOptions, error) {
	if options == nil || options.Server == "" {
		return adapter.DNSQueryOptions{}, nil
	}
	transportManager := service.FromContext[adapter.DNSTransportManager](ctx)
	transport, loaded := transportManager.Transport(options.Server)
	if !loaded {
		return adapter.DNSQueryOptions{}, E.New("domain resolver not found: " + options.Server)
	}
	return adapter.DNSQueryOptions{
		Transport:              transport,
		Strategy:               C.DomainStrategy(options.Strategy),
		DisableCache:           options.DisableCache,
		DisableOptimisticCache: options.DisableOptimisticCache,
		RewriteTTL:             options.RewriteTTL,
		Timeout:                time.Duration(options.Timeout),
		ClientSubnet:           options.ClientSubnet.Build(netip.Prefix{}),
	}, nil
}
