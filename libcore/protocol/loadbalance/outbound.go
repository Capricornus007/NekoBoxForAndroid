package loadbalance

import (
	"context"
	"math/rand"
	"net"
	"slices"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const TypeLoadBalance = "loadbalance"

// candidateDialTimeout caps one non-final attempt, so a black-holed member cannot stall a
// connection while the group still has healthy outbounds left to try. The last candidate is
// dialled with the caller's own context.
const candidateDialTimeout = 3500 * time.Millisecond

// LoadBalanceOptions is the selector options plus "strategy": the Kotlin side writes
// strategy= into loadbalance outbounds (consistent_hash / leastLoad / round_robin / random),
// and it needs a field of its own here - option.SelectorOutboundOptions has none.
type LoadBalanceOptions struct {
	option.SelectorOutboundOptions
	Strategy string `json:"strategy,omitempty"`
}

func RegisterLoadBalance(registry *outbound.Registry) {
	outbound.Register[LoadBalanceOptions](registry, TypeLoadBalance, NewLoadBalance)
}

var (
	_ adapter.Outbound                = (*LoadBalance)(nil)
	_ adapter.ConnectionHandler       = (*LoadBalance)(nil)
	_ adapter.PacketConnectionHandler = (*LoadBalance)(nil)
	_ adapter.Referrer                = (*LoadBalance)(nil)
)

type LoadBalance struct {
	outbound.Adapter
	ctx            context.Context
	outbound       adapter.OutboundManager
	connection     adapter.ConnectionManager
	logger         logger.ContextLogger
	tags           []string
	strategy       string
	outbounds      []adapter.Outbound
	counter        uint64
	activeConns    []*atomic.Int64
	interruptGroup *interrupt.Group
}

func NewLoadBalance(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options LoadBalanceOptions) (adapter.Outbound, error) {
	strategy := options.Strategy
	if strategy == "" {
		// Destination stickiness is the default, same as before: an upload, a WebSocket or a
		// resumed TLS session must not hop between nodes mid-flight.
		strategy = "consistent_hash"
	}
	lb := &LoadBalance{
		Adapter:        outbound.NewAdapter(TypeLoadBalance, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:            ctx,
		outbound:       service.FromContext[adapter.OutboundManager](ctx),
		connection:     service.FromContext[adapter.ConnectionManager](ctx),
		logger:         logger,
		tags:           options.Outbounds,
		strategy:       strategy,
		interruptGroup: interrupt.NewGroup(),
	}
	if len(lb.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return lb, nil
}

// References reports every member, not just the last picked one: route/reference.go feeds
// this into SetKeepIdleConnections, so a member left out of it would have its idle
// connection pool dropped even though the group keeps routing to it.
func (s *LoadBalance) References() []string {
	return s.tags
}

func (s *LoadBalance) Start() error {
	s.outbounds = make([]adapter.Outbound, 0, len(s.tags))
	s.activeConns = make([]*atomic.Int64, len(s.tags))
	for i, tag := range s.tags {
		detour, loaded := s.outbound.Outbound(tag)
		if !loaded {
			return E.New("outbound ", i, " not found: ", tag)
		}
		s.outbounds = append(s.outbounds, detour)
		s.activeConns[i] = new(atomic.Int64)
	}
	return nil
}

// hashDestination is FNV-1a over the destination host, ported from own/dcd786819.
func hashDestination(dest M.Socksaddr) uint32 {
	var key string
	if dest.Fqdn != "" {
		key = dest.Fqdn
	} else if dest.IsIP() {
		key = dest.Addr.String()
	} else {
		key = dest.String()
	}
	var h uint32 = 2166136261
	for i := 0; i < len(key); i++ {
		h ^= uint32(key[i])
		h *= 16777619
	}
	return h
}

// candidateIndices returns the members in the order they should be tried. Hashing the
// destination keeps every connection to the same server on the same node, which is what
// stops chunked/multipart uploads, WebSockets and TLS session resumption from hopping
// across nodes; the rotation after the preferred one is what makes a dead node survivable.
func (s *LoadBalance) candidateIndices(destination M.Socksaddr) []int {
	n := len(s.outbounds)
	if n == 0 {
		return nil
	}
	indices := make([]int, n)
	for i := range indices {
		indices[i] = i
	}
	switch s.strategy {
	case "leastLoad":
		// Fewest live connections first; ties keep the tag order, so behaviour stays
		// predictable while a node is not measured.
		slices.SortStableFunc(indices, func(a, b int) int {
			return cmpInt64(s.activeConns[a].Load(), s.activeConns[b].Load())
		})
	case "random":
		start := rand.Intn(n)
		for i := range indices {
			indices[i] = (start + i) % n
		}
	case "round_robin", "roundRobin":
		start := int(atomic.AddUint64(&s.counter, 1) % uint64(n))
		for i := range indices {
			indices[i] = (start + i) % n
		}
	default: // consistent_hash
		if destination.Fqdn != "" || destination.IsIP() {
			start := int(hashDestination(destination) % uint32(n))
			for i := range indices {
				indices[i] = (start + i) % n
			}
		} else {
			start := int(atomic.AddUint64(&s.counter, 1) % uint64(n))
			for i := range indices {
				indices[i] = (start + i) % n
			}
		}
	}
	return indices
}

func cmpInt64(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

// trackedConn / trackedPacketConn keep the leastLoad counters honest: a connection is only
// counted against the member that actually carries it, and released exactly once on Close.
type trackedConn struct {
	net.Conn
	onClose func()
	closed  atomic.Bool
}

func (c *trackedConn) Close() error {
	if c.closed.CompareAndSwap(false, true) && c.onClose != nil {
		c.onClose()
	}
	return c.Conn.Close()
}

type trackedPacketConn struct {
	net.PacketConn
	onClose func()
	closed  atomic.Bool
}

func (c *trackedPacketConn) Close() error {
	if c.closed.CompareAndSwap(false, true) && c.onClose != nil {
		c.onClose()
	}
	return c.PacketConn.Close()
}

func (s *LoadBalance) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	indices := s.candidateIndices(destination)
	n := len(indices)
	if n == 0 {
		return nil, E.New("no outbounds available")
	}
	var lastErr error
	for i, idx := range indices {
		candidate := s.outbounds[idx]
		var (
			conn net.Conn
			err  error
		)
		if i < n-1 {
			candidateCtx, cancel := context.WithTimeout(ctx, candidateDialTimeout)
			conn, err = candidate.DialContext(candidateCtx, network, destination)
			cancel()
		} else {
			conn, err = candidate.DialContext(ctx, network, destination)
		}
		if err == nil {
			if s.strategy == "leastLoad" {
				s.activeConns[idx].Add(1)
				conn = &trackedConn{Conn: conn, onClose: func() { s.activeConns[idx].Add(-1) }}
			}
			return s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		lastErr = err
		s.logger.WarnContext(ctx, "loadbalance[", candidate.Tag(), "]: ", err)
	}
	return nil, lastErr
}

func (s *LoadBalance) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	indices := s.candidateIndices(destination)
	n := len(indices)
	if n == 0 {
		return nil, E.New("no outbounds available")
	}
	var lastErr error
	for i, idx := range indices {
		candidate := s.outbounds[idx]
		var (
			conn net.PacketConn
			err  error
		)
		if i < n-1 {
			candidateCtx, cancel := context.WithTimeout(ctx, candidateDialTimeout)
			conn, err = candidate.ListenPacket(candidateCtx, destination)
			cancel()
		} else {
			conn, err = candidate.ListenPacket(ctx, destination)
		}
		if err == nil {
			if s.strategy == "leastLoad" {
				s.activeConns[idx].Add(1)
				conn = &trackedPacketConn{PacketConn: conn, onClose: func() { s.activeConns[idx].Add(-1) }}
			}
			return s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		lastErr = err
		s.logger.WarnContext(ctx, "loadbalance[", candidate.Tag(), "]: ", err)
	}
	return nil, lastErr
}

// NewConnection goes through the connection manager with the group itself as the dialer, so
// the handler path gets the same strategy, failover and per-candidate timeout as the
// dial path instead of being pinned to one member with no way out.
func (s *LoadBalance) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *LoadBalance) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}

func (s *LoadBalance) Close() error {
	if s.interruptGroup != nil {
		s.interruptGroup.Interrupt(true)
	}
	return nil
}
