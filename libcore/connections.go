package libcore

import (
	"context"
	"encoding/json"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	N "github.com/sagernet/sing/common/network"
)

// ConnectionEntry is one routed connection as seen by the router: what asked for it, where it
// went, which rule and outbound handled it and what the resolver answered. Exported as JSON.
type ConnectionEntry struct {
	ID          uint64   `json:"id"`
	Start       int64    `json:"start"`
	End         int64    `json:"end,omitempty"`
	Network     string   `json:"network"`
	Inbound     string   `json:"inbound"`
	Source      string   `json:"source"`
	Destination string   `json:"destination"`
	Domain      string   `json:"domain,omitempty"`
	Addresses   []string `json:"addresses,omitempty"`
	FakeIP      bool     `json:"fakeip,omitempty"`
	Protocol    string   `json:"protocol,omitempty"`
	UID         int32    `json:"uid,omitempty"`
	Package     string   `json:"package,omitempty"`
	Rule        string   `json:"rule"`
	Outbound    string   `json:"outbound"`
	CloseReason string   `json:"closeReason,omitempty"`
	Upload      int64    `json:"upload"`
	Download    int64    `json:"download"`

	upload   *atomic.Int64
	download *atomic.Int64
}

func (e *ConnectionEntry) snapshot() ConnectionEntry {
	copied := *e
	copied.Upload = e.upload.Load()
	copied.Download = e.download.Load()
	return copied
}

// connectionTracker keeps live connections and, when history is enabled, a bounded list of
// closed ones. It is appended to the router next to the stats tracker.
type connectionTracker struct {
	mu           sync.Mutex
	nextID       uint64
	active       map[uint64]*ConnectionEntry
	history      []*ConnectionEntry
	historyLimit int
}

func newConnectionTracker() *connectionTracker {
	return &connectionTracker{active: make(map[uint64]*ConnectionEntry)}
}

func (t *connectionTracker) open(metadata adapter.InboundContext, rule string, outbound string) *ConnectionEntry {
	entry := &ConnectionEntry{
		Start:       time.Now().UnixMilli(),
		Network:     metadata.Network,
		Inbound:     metadata.Inbound,
		Source:      metadata.Source.String(),
		Destination: metadata.Destination.String(),
		Domain:      metadata.Domain,
		FakeIP:      metadata.FakeIP,
		Protocol:    metadata.Protocol,
		Rule:        rule,
		Outbound:    outbound,
		upload:      new(atomic.Int64),
		download:    new(atomic.Int64),
	}
	if entry.Domain == "" && metadata.Destination.IsFqdn() {
		entry.Domain = metadata.Destination.Fqdn
	}
	for _, addr := range metadata.DestinationAddresses {
		entry.Addresses = append(entry.Addresses, addr.String())
	}
	if metadata.ProcessInfo != nil {
		entry.UID = metadata.ProcessInfo.UserId
		if len(metadata.ProcessInfo.AndroidPackageNames) > 0 {
			entry.Package = metadata.ProcessInfo.AndroidPackageNames[0]
		}
	}
	t.mu.Lock()
	t.nextID++
	entry.ID = t.nextID
	t.active[entry.ID] = entry
	t.mu.Unlock()
	return entry
}

func (t *connectionTracker) close(entry *ConnectionEntry, reason string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, ok := t.active[entry.ID]; !ok {
		return
	}
	delete(t.active, entry.ID)
	if t.historyLimit <= 0 {
		return
	}
	entry.End = time.Now().UnixMilli()
	entry.CloseReason = reason
	t.history = append(t.history, entry)
	if len(t.history) > t.historyLimit {
		t.history = t.history[len(t.history)-t.historyLimit:]
	}
}

func (t *connectionTracker) setHistoryLimit(limit int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.historyLimit = limit
	if limit <= 0 {
		t.history = nil
	} else if len(t.history) > limit {
		t.history = t.history[len(t.history)-limit:]
	}
}

func (t *connectionTracker) list(includeClosed bool) []ConnectionEntry {
	t.mu.Lock()
	defer t.mu.Unlock()
	entries := make([]ConnectionEntry, 0, len(t.active)+len(t.history))
	for _, entry := range t.active {
		entries = append(entries, entry.snapshot())
	}
	if includeClosed {
		for _, entry := range t.history {
			entries = append(entries, entry.snapshot())
		}
	}
	return entries
}

func ruleString(rule adapter.Rule) string {
	if rule == nil {
		return ""
	}
	return rule.String()
}

func outboundTag(outbound adapter.Outbound) string {
	if outbound == nil {
		return ""
	}
	return outbound.Tag()
}

func (t *connectionTracker) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) net.Conn {
	entry := t.open(metadata, ruleString(matchedRule), outboundTag(matchOutbound))
	return &trackedConn{
		CounterConn: bufio.NewInt64CounterConn(conn, []*atomic.Int64{entry.upload}, []*atomic.Int64{entry.download}),
		tracker:     t,
		entry:       entry,
	}
}

func (t *connectionTracker) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) N.PacketConn {
	entry := t.open(metadata, ruleString(matchedRule), outboundTag(matchOutbound))
	return &trackedPacketConn{
		CounterPacketConn: bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{entry.upload}, nil, []*atomic.Int64{entry.download}, nil),
		tracker:           t,
		entry:             entry,
	}
}

func (t *connectionTracker) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) tun.FlowTracker {
	return &trackedFlow{tracker: t, entry: t.open(metadata, ruleString(matchedRule), outboundTag(matchOutbound))}
}

type trackedConn struct {
	*bufio.CounterConn
	tracker *connectionTracker
	entry   *ConnectionEntry
}

func (c *trackedConn) Close() error {
	c.tracker.close(c.entry, "closed")
	return c.CounterConn.Close()
}

type trackedPacketConn struct {
	*bufio.CounterPacketConn
	tracker *connectionTracker
	entry   *ConnectionEntry
}

func (c *trackedPacketConn) Close() error {
	c.tracker.close(c.entry, "closed")
	return c.CounterPacketConn.Close()
}

type trackedFlow struct {
	tracker *connectionTracker
	entry   *ConnectionEntry
}

func (f *trackedFlow) AttachFlow(tun.FlowHandle) {}
func (f *trackedFlow) FlowEstablished()          {}
func (f *trackedFlow) CountForward(n int)        { f.entry.upload.Add(int64(n)) }
func (f *trackedFlow) CountReverse(n int)        { f.entry.download.Add(int64(n)) }
func (f *trackedFlow) CloseFlow(reason tun.FlowCloseReason) {
	f.tracker.close(f.entry, reason.String())
}

// Connections returns the live connections, plus the bounded closed history when
// includeClosed is set, as a JSON array of ConnectionEntry.
func (b *BoxInstance) Connections(includeClosed bool) string {
	if b.connections == nil {
		return "[]"
	}
	data, err := json.Marshal(b.connections.list(includeClosed))
	if err != nil {
		return "[]"
	}
	return string(data)
}

// SetConnectionHistory keeps up to limit closed connections (0 disables history).
func (b *BoxInstance) SetConnectionHistory(limit int32) {
	if b.connections != nil {
		b.connections.setHistoryLimit(int(limit))
	}
}
