package loadbalance

import (
	"context"
	"net"
	"sync/atomic"

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

func RegisterLoadBalance(registry *outbound.Registry) {
	outbound.Register[option.SelectorOutboundOptions](registry, TypeLoadBalance, NewLoadBalance)
}

var (
	_ adapter.Outbound                = (*LoadBalance)(nil)
	_ adapter.ConnectionHandler       = (*LoadBalance)(nil)
	_ adapter.PacketConnectionHandler = (*LoadBalance)(nil)
)

type LoadBalance struct {
	outbound.Adapter
	ctx            context.Context
	outbound       adapter.OutboundManager
	connection     adapter.ConnectionManager
	logger         logger.ContextLogger
	tags           []string
	outbounds      []adapter.Outbound
	counter        uint64
	interruptGroup *interrupt.Group
}

func NewLoadBalance(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.SelectorOutboundOptions) (adapter.Outbound, error) {
	lb := &LoadBalance{
		Adapter:        outbound.NewAdapter(TypeLoadBalance, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:            ctx,
		outbound:       service.FromContext[adapter.OutboundManager](ctx),
		connection:     service.FromContext[adapter.ConnectionManager](ctx),
		logger:         logger,
		tags:           options.Outbounds,
		interruptGroup: interrupt.NewGroup(),
	}
	if len(lb.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return lb, nil
}

func (s *LoadBalance) Start() error {
	s.outbounds = make([]adapter.Outbound, 0, len(s.tags))
	for i, tag := range s.tags {
		detour, loaded := s.outbound.Outbound(tag)
		if !loaded {
			return E.New("outbound ", i, " not found: ", tag)
		}
		s.outbounds = append(s.outbounds, detour)
	}
	return nil
}

func (s *LoadBalance) pick() adapter.Outbound {
	n := len(s.outbounds)
	if n == 0 {
		return nil
	}
	idx := atomic.AddUint64(&s.counter, 1) % uint64(n)
	return s.outbounds[idx]
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

// startIndexOf returns the outbound that owns destination: hashing the host keeps every
// connection to the same server on the same node, which is what stops chunked/multipart
// uploads, WebSockets and TLS session resumption from hopping across nodes.
func (s *LoadBalance) startIndexOf(destination M.Socksaddr, n int) int {
	if destination.Fqdn != "" || destination.IsIP() {
		return int(hashDestination(destination) % uint32(n))
	}
	return int(atomic.AddUint64(&s.counter, 1) % uint64(n))
}

// pickByDestination is used by the handler path, where the connection is already open and
// only the metadata carries the destination.
func (s *LoadBalance) pickByDestination(destination M.Socksaddr) adapter.Outbound {
	n := len(s.outbounds)
	if n == 0 {
		return nil
	}
	return s.outbounds[s.startIndexOf(destination, n)]
}

func (s *LoadBalance) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	n := len(s.outbounds)
	if n == 0 {
		return nil, E.New("no outbounds available")
	}
	startIdx := s.startIndexOf(destination, n)
	var lastErr error
	for i := 0; i < n; i++ {
		candidate := s.outbounds[(startIdx+i)%n]
		conn, err := candidate.DialContext(ctx, network, destination)
		if err == nil {
			return s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (s *LoadBalance) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	n := len(s.outbounds)
	if n == 0 {
		return nil, E.New("no outbounds available")
	}
	startIdx := s.startIndexOf(destination, n)
	var lastErr error
	for i := 0; i < n; i++ {
		candidate := s.outbounds[(startIdx+i)%n]
		conn, err := candidate.ListenPacket(ctx, destination)
		if err == nil {
			return s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (s *LoadBalance) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	selected := s.pickByDestination(metadata.Destination)
	if selected == nil {
		conn.Close()
		return
	}
	if outboundHandler, isHandler := selected.(adapter.ConnectionHandler); isHandler {
		outboundHandler.NewConnection(ctx, conn, metadata, onClose)
	} else {
		s.connection.NewConnection(ctx, selected, conn, metadata, onClose)
	}
}

func (s *LoadBalance) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	selected := s.pickByDestination(metadata.Destination)
	if selected == nil {
		conn.Close()
		return
	}
	if outboundHandler, isHandler := selected.(adapter.PacketConnectionHandler); isHandler {
		outboundHandler.NewPacketConnection(ctx, conn, metadata, onClose)
	} else {
		s.connection.NewPacketConnection(ctx, selected, conn, metadata, onClose)
	}
}

func (s *LoadBalance) Close() error {
	if s.interruptGroup != nil {
		s.interruptGroup.Interrupt(true)
	}
	return nil
}

