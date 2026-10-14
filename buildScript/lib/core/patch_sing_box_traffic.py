#!/usr/bin/env python3
"""Attribute group traffic to the actual selected outbound, retaining session totals."""
from pathlib import Path
import sys
import re
import subprocess

root = Path(sys.argv[1])
def patch(path, old, new):
    file = root / path
    new = re.sub(r"(?m)^( +)", lambda match: "\t" * (len(match[0]) // 4), new)
    text = file.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f'Traffic patch anchor missing: {path}')
    file.write_text(text.replace(old, new, 1))

(root / 'adapter/outbound_stats.go').write_text('''package adapter

import (
    "net"
    N "github.com/sagernet/sing/common/network"
)

// OutboundStatsTracker counts a group's actual child without counting the
// session twice. directIn distinguishes accepted and dialed connections.
type OutboundStatsTracker interface {
    TrackOutboundConnection(tag string, conn net.Conn, directIn bool) net.Conn
    TrackOutboundPacketConnection(tag string, conn N.PacketConn, directIn bool) N.PacketConn
}
''')
(root / 'boxapi/outbound_stats.go').write_text('''package boxapi

import (
    "net"
    "strings"
    "sync/atomic"
    "github.com/sagernet/sing-box/adapter"
    "github.com/sagernet/sing/common/bufio"
    N "github.com/sagernet/sing/common/network"
)

func (s *SbStatsService) TrackOutboundConnection(tag string, conn net.Conn, directIn bool) net.Conn {
    return s.RoutedConnectionInternal("", tag, "", conn, directIn)
}

func (s *SbStatsService) TrackOutboundPacketConnection(tag string, conn N.PacketConn, directIn bool) N.PacketConn {
    if !s.outbounds[tag] { return conn }
    s.access.Lock()
    up := s.loadOrCreateCounter("outbound>>>" + tag + ">>>traffic>>>uplink")
    down := s.loadOrCreateCounter("outbound>>>" + tag + ">>>traffic>>>downlink")
    s.access.Unlock()
    if !directIn { up, down = down, up }
    return bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{up}, nil, []*atomic.Int64{down}, nil)
}

func (s *SbStatsService) sessionCounters(metadata adapter.InboundContext, tag string) ([]*atomic.Int64, []*atomic.Int64) {
    // Plugin mapping connections carry encrypted transport for an already
    // counted user connection. They must not inflate session traffic.
    if strings.HasPrefix(metadata.Inbound, "c-") && strings.Contains(metadata.Inbound, "-mapping-") { return nil, nil }
    category := "proxy"
    if tag == "bypass" || tag == "direct" { category = "direct" }
    s.access.Lock()
    defer s.access.Unlock()
    return []*atomic.Int64{s.loadOrCreateCounter("session>>>" + category + ">>>traffic>>>uplink")},
        []*atomic.Int64{s.loadOrCreateCounter("session>>>" + category + ">>>traffic>>>downlink")}
}

func (s *SbStatsService) trackSessionConnection(metadata adapter.InboundContext, tag string, conn net.Conn) net.Conn {
    up, down := s.sessionCounters(metadata, tag)
    return bufio.NewInt64CounterConn(conn, up, down)
}

func (s *SbStatsService) trackSessionPacketConnection(metadata adapter.InboundContext, tag string, conn N.PacketConn) N.PacketConn {
    up, down := s.sessionCounters(metadata, tag)
    return bufio.NewInt64CounterPacketConn(conn, up, nil, down, nil)
}
''')
patch('boxapi/v2ray_stats_service.go',
    '\treturn s.RoutedConnectionInternal(inbound, outbound, user, conn, true)',
    '\treturn s.trackSessionConnection(metadata, outbound, s.RoutedConnectionInternal(inbound, outbound, user, conn, true))')
patch('boxapi/v2ray_stats_service.go',
    '''func (s *SbStatsService) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) N.PacketConn {
''',
    '''func (s *SbStatsService) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) N.PacketConn {
    conn = s.trackSessionPacketConnection(metadata, matchOutbound.Tag(), conn)
''')
# Look the tracker up at dial/delegation time: the UI attaches it after box.New
# but before box.Start. Health checks have no inbound context and are excluded.
(root / 'protocol/group/outbound_stats.go').write_text('''package group

import (
    "context"
    "net"
    "github.com/sagernet/sing-box/adapter"
    "github.com/sagernet/sing/common/bufio"
    "github.com/sagernet/sing/service"
)

func trackGroupDial(ctx context.Context, tag string, conn net.Conn) net.Conn {
    tracker := service.FromContext[adapter.OutboundStatsTracker](ctx)
    if tracker == nil || adapter.ContextFrom(ctx) == nil { return conn }
    return tracker.TrackOutboundConnection(tag, conn, false)
}

func trackGroupPacketDial(ctx context.Context, tag string, conn net.PacketConn) net.PacketConn {
    tracker := service.FromContext[adapter.OutboundStatsTracker](ctx)
    if tracker == nil || adapter.ContextFrom(ctx) == nil { return conn }
    return bufio.NewNetPacketConn(tracker.TrackOutboundPacketConnection(tag, bufio.NewPacketConn(conn), false))
}
''')
patch('protocol/group/selector.go',
    '\tconn, err := s.selected.Load().DialContext(ctx, network, destination)',
    '\tselected := s.selected.Load()\n\tconn, err := selected.DialContext(ctx, network, destination)')
patch('protocol/group/selector.go',
    '\treturn s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil',
    '\tconn = trackGroupDial(ctx, selected.Tag(), conn)\n\treturn s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil')
patch('protocol/group/selector.go',
    '\tconn, err := s.selected.Load().ListenPacket(ctx, destination)',
    '\tselected := s.selected.Load()\n\tconn, err := selected.ListenPacket(ctx, destination)')
patch('protocol/group/selector.go',
    '\treturn s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil',
    '\tconn = trackGroupPacketDial(ctx, selected.Tag(), conn)\n\treturn s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil')
patch('protocol/group/selector.go',
    '''\tselected := s.selected.Load()
\tif outboundHandler, isHandler := selected.(adapter.ConnectionHandlerEx); isHandler {''',
    '''\tselected := s.selected.Load()
    if tracker := service.FromContext[adapter.OutboundStatsTracker](s.ctx); tracker != nil {
        conn = tracker.TrackOutboundConnection(selected.Tag(), conn, true)
    }
\tif outboundHandler, isHandler := selected.(adapter.ConnectionHandlerEx); isHandler {''')
patch('protocol/group/selector.go',
    '''\tselected := s.selected.Load()
\tif outboundHandler, isHandler := selected.(adapter.PacketConnectionHandlerEx); isHandler {''',
    '''\tselected := s.selected.Load()
    if tracker := service.FromContext[adapter.OutboundStatsTracker](s.ctx); tracker != nil {
        conn = tracker.TrackOutboundPacketConnection(selected.Tag(), conn, true)
    }
\tif outboundHandler, isHandler := selected.(adapter.PacketConnectionHandlerEx); isHandler {''')
patch('protocol/group/urltest.go',
    '\t\treturn s.group.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil',
    '\t\tconn = trackGroupDial(ctx, outbound.Tag(), conn)\n\t\treturn s.group.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil')
patch('protocol/group/urltest.go',
    '\t\treturn s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil',
    '\t\tconn = trackGroupPacketDial(ctx, outbound.Tag(), conn)\n\t\treturn s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil')

subprocess.run(['gofmt', '-w'] + [str(root / file) for file in [
    'adapter/outbound_stats.go', 'boxapi/outbound_stats.go', 'boxapi/v2ray_stats_service.go',
    'protocol/group/outbound_stats.go', 'protocol/group/selector.go', 'protocol/group/urltest.go',
]], check=True)
