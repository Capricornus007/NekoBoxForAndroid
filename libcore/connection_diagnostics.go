package libcore

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	boxlog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-tun"
	N "github.com/sagernet/sing/common/network"
)

// A bounded in-memory ring, created only when the core log level is debug/trace.
// DNS and group selection use the same connection ID when available. Resolver
// requests without that ID remain separate: a domain alone is not attribution.
type connectionDiagnostics struct {
	access  sync.Mutex
	entries []map[string]any
	next    int
}

func diagnosticText(s string) string {
	if len(s) > 1024 {
		return s[:1024]
	}
	return s
}
func diagnosticID(ctx context.Context) string {
	if id, ok := boxlog.IDFromContext(ctx); ok {
		return fmt.Sprintf("%d/%d", id.ID, id.CreatedAt.UnixNano())
	}
	return ""
}
func (d *connectionDiagnostics) add(ctx context.Context, entry map[string]any) {
	entry["time"] = time.Now().UTC().Format(time.RFC3339Nano)
	entry["id"] = diagnosticID(ctx)
	if metadata := adapter.ContextFrom(ctx); metadata != nil && metadata.ProcessInfo != nil {
		entry["uid"] = metadata.ProcessInfo.UserId
		packages := metadata.ProcessInfo.AndroidPackageNames
		if len(packages) > 8 {
			packages = packages[:8]
		}
		entry["packages"] = append([]string(nil), packages...)
	}
	d.access.Lock()
	defer d.access.Unlock()
	if len(d.entries) < 128 {
		d.entries = append(d.entries, entry)
	} else {
		d.entries[d.next] = entry
		d.next = (d.next + 1) % 128
	}
}
func (d *connectionDiagnostics) snapshot() []map[string]any {
	d.access.Lock()
	defer d.access.Unlock()
	result := make([]map[string]any, 0, len(d.entries))
	result = append(result, d.entries[d.next:]...)
	result = append(result, d.entries[:d.next]...)
	return result
}
func (d *connectionDiagnostics) route(ctx context.Context, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) {
	entry := map[string]any{"kind": "route", "network": metadata.Network, "domain": diagnosticText(metadata.Domain), "destination": metadata.Destination.String(), "inbound": metadata.Inbound, "rule": "final"}
	if rule != nil {
		entry["rule"] = diagnosticText(rule.String())
	}
	if outbound != nil {
		entry["outbound"] = outbound.Tag()
		entry["outbound_type"] = outbound.Type()
	}
	if metadata.ProcessInfo != nil {
		entry["uid"] = metadata.ProcessInfo.UserId
		entry["packages"] = append([]string(nil), metadata.ProcessInfo.AndroidPackageNames...)
	}
	d.add(ctx, entry)
}
func (d *connectionDiagnostics) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) net.Conn {
	d.route(ctx, metadata, rule, outbound)
	return conn
}
func (d *connectionDiagnostics) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) N.PacketConn {
	d.route(ctx, metadata, rule, outbound)
	return conn
}
func (d *connectionDiagnostics) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) tun.FlowTracker {
	d.route(ctx, metadata, rule, outbound)
	return nil
}
func (d *connectionDiagnostics) GroupDial(ctx context.Context, selected string) {
	d.add(ctx, map[string]any{"kind": "selected", "outbound": diagnosticText(selected)})
}
func (d *connectionDiagnostics) DNSQuery(ctx context.Context, domain, server string, queryType uint16, rcode int, cached bool, err error) {
	entry := map[string]any{"kind": "dns", "domain": diagnosticText(domain), "server": diagnosticText(server), "query_type": queryType, "rcode": rcode, "cached": cached}
	// Error text can include URLs/credentials from a transport. Keep the result only.
	entry["failed"] = err != nil
	d.add(ctx, entry)
}
