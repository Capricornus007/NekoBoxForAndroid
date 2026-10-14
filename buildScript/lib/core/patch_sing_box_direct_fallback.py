#!/usr/bin/env python3
"""Reachability-based direct fallback, isolated from latency-test history."""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
p = root / 'common/urltest/urltest.go'
s = p.read_text()
if 'func URLTestReachable(' not in s:
    s = s.replace('"crypto/tls"', '"crypto/tls"\n\t"fmt"')
    s = s.replace('func URLTest(ctx context.Context, link string, detour N.Dialer) (t uint16, err error) {', '''func URLTest(ctx context.Context, link string, detour N.Dialer) (uint16, error) {
	return urlTest(ctx, link, detour, false)
}

// Reachability checks reject blocking/error responses without changing latency tests.
func URLTestReachable(ctx context.Context, link string, detour N.Dialer) (uint16, error) {
	return urlTest(ctx, link, detour, true)
}

func urlTest(ctx context.Context, link string, detour N.Dialer, reachable bool) (t uint16, err error) {''')
    s = s.replace('\tresp.Body.Close()\n', '''	resp.Body.Close()
	if reachable && (resp.StatusCode < 200 || resp.StatusCode >= 400) {
		return 0, fmt.Errorf("control site returned HTTP %d", resp.StatusCode)
	}
''')
    p.write_text(s)

p = root / 'protocol/group/urltest.go'
s = p.read_text()
if 'strategy == "direct-fallback"' in s:
    sys.exit(0)

def replace(old, new):
    global s
    if old not in s:
        raise RuntimeError('missing urltest anchor: ' + old[:100])
    s = s.replace(old, new, 1)

replace('strategy != "fastest" && strategy != "priority"', 'strategy != "fastest" && strategy != "priority" && strategy != "direct-fallback"')
replace('\tvar history adapter.URLTestHistoryStorage', '''	if strategy == "direct-fallback" && (len(outbounds) != 2 || managedByParent) {
		return nil, E.New("direct-fallback requires two outbounds and its own checks")
	}
	var history adapter.URLTestHistoryStorage''')
replace('\treturn &URLTestGroup{', '''	if strategy == "direct-fallback" {
		// Other groups can probe the same direct tag with a different control URL.
		history = urltest.NewHistoryStorage()
	}
	return &URLTestGroup{''')
replace('func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {', '''func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
	if g.strategy == "direct-fallback" {
		index := 1
		if g.history.LoadURLTestHistory(g.outbounds[0].Tag()) != nil {
			index = 0
		}
		selected := g.outbounds[index]
		if common.Contains(selected.Network(), network) {
			return selected, true
		}
		return nil, false
	}''')
replace('\tdefer g.checking.Store(false)\n', '''	defer g.checking.Store(false)
	if g.strategy == "direct-fallback" {
		// The proxy is a fallback, not another probe candidate: no latency ranking.
		direct := g.outbounds[0]
		testCtx, cancel := context.WithTimeout(ctx, g.timeout)
		delay, err := urltest.URLTestReachable(testCtx, g.link, direct)
		cancel()
		if err != nil {
			g.history.DeleteURLTestHistory(direct.Tag())
		} else {
			g.history.StoreURLTestHistory(direct.Tag(), &adapter.URLTestHistory{Time: time.Now(), Delay: delay})
			result[direct.Tag()] = delay
		}
		g.performUpdateCheck()
		return result, nil
	}
''')
# Traffic and scheduled health checks access the selection concurrently.
s = re.sub(r'((?:s\.group|g)\.selectedOutbound(?:TCP|UDP))(?![\w.])', r'\1.Load()', s)
s = re.sub(r'(g\.selectedOutbound(?:TCP|UDP))\.Load\(\) = ([^\n]+)', r'\1.Store(\2)', s)
s = re.sub(r'(selectedOutbound(?:TCP|UDP)\s+)adapter.Outbound', r'\1common.TypedValue[adapter.Outbound]', s)
s = s.replace('s.group.selectedOutboundTCP.Tag()', 's.group.selectedOutboundTCP.Load().Tag()')
s = s.replace('s.group.selectedOutboundUDP.Tag()', 's.group.selectedOutboundUDP.Load().Tag()')
# Snapshot once: a priority group can clear its selection during a failed check.
s = s.replace('if s.group.selectedOutboundTCP.Load() != nil {\n\t\treturn s.group.selectedOutboundTCP.Load().Tag()',
              'if selected := s.group.selectedOutboundTCP.Load(); selected != nil {\n\t\treturn selected.Tag()')
s = s.replace('if s.group.selectedOutboundUDP.Load() != nil {\n\t\treturn s.group.selectedOutboundUDP.Load().Tag()',
              'if selected := s.group.selectedOutboundUDP.Load(); selected != nil {\n\t\treturn selected.Tag()')
for network in ('TCP', 'UDP'):
    s = s.replace(f'if g.selectedOutbound{network}.Load() != nil {{\n\t\t\tif history := g.history.LoadURLTestHistory(RealTag(g.selectedOutbound{network}.Load())); history != nil {{\n\t\t\t\tminOutbound = g.selectedOutbound{network}.Load()',
                  f'if selected := g.selectedOutbound{network}.Load(); selected != nil {{\n\t\t\tif history := g.history.LoadURLTestHistory(RealTag(selected)); history != nil {{\n\t\t\t\tminOutbound = selected')
replace('\tstarted                      bool', '\tstarted                      bool\n\tclosed                       bool')
replace('''	g.access.Lock()
	defer g.access.Unlock()
	g.started = true''', '''	g.access.Lock()
	g.started = true''')
replace('''		go g.CheckOutbounds(false)
	}
}

func (g *URLTestGroup) Touch''', '''		go g.CheckOutbounds(false)
	}
	g.access.Unlock()
	if g.strategy == "direct-fallback" {
		g.Touch()
	}
}

func (g *URLTestGroup) Touch''')
replace('''	if !g.started {
		return
	}
	g.access.Lock()
	defer g.access.Unlock()''', '''	g.access.Lock()
	defer g.access.Unlock()
	if !g.started || g.closed {
		return
	}''')
replace('''	if g.ticker == nil {
		return nil
	}
	g.ticker.Stop()
	g.pause.UnregisterCallback(g.pauseCallback)
	close(g.close)''', '''	if g.closed {
		return nil
	}
	g.closed = true
	if g.ticker != nil {
		g.ticker.Stop()
		g.pause.UnregisterCallback(g.pauseCallback)
	}
	close(g.close)''')
replace('g.pauseCallback = pause.RegisterTicker(g.pause, g.ticker, g.interval, nil)', '''var resume func()
	if g.strategy == "direct-fallback" {
		resume = func() { go g.CheckOutbounds(true) }
	}
	g.pauseCallback = pause.RegisterTicker(g.pause, g.ticker, g.interval, resume)''')
replace('if time.Since(g.lastActive.Load()) > g.idleTimeout {', 'if g.strategy != "direct-fallback" && time.Since(g.lastActive.Load()) > g.idleTimeout {')
replace('''	if updated {
		g.interruptGroup.Interrupt''', '''	if updated {
		if g.strategy == "direct-fallback" && outboundTCP != nil {
			g.logger.Info("direct fallback selected: ", outboundTCP.Tag())
		}
		g.interruptGroup.Interrupt''')
p.write_text(s)

# Attribute session totals to the selected path while preserving group counters.
p = root / 'boxapi/v2ray_stats_service.go'
s = p.read_text()
s = s.replace('s.trackSessionConnection(metadata, outbound,', 's.trackSessionConnection(metadata, sessionOutboundTag(matchOutbound),')
s = s.replace('s.trackSessionPacketConnection(metadata, matchOutbound.Tag(),', 's.trackSessionPacketConnection(metadata, sessionOutboundTag(matchOutbound),')
p.write_text(s)
p = root / 'boxapi/outbound_stats.go'
s = p.read_text()
if 'func sessionOutboundTag(' not in s:
    s += '''
func sessionOutboundTag(outbound adapter.Outbound) string {
    if outbound.Tag() == "direct-fallback" {
        if group, ok := outbound.(adapter.OutboundGroup); ok && (group.Now() == "direct" || group.Now() == "bypass") {
            return "direct"
        }
    }
    return outbound.Tag()
}
'''
    p.write_text(s)
