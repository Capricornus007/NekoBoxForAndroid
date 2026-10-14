#!/usr/bin/env python3
"""Read-only snapshots of group decisions; leave selection behavior unchanged."""
import pathlib
import sys

p = pathlib.Path(sys.argv[1]) / 'protocol/group/urltest.go'
s = p.read_text()
if 'func (s *URLTest) SelectionDiagnostics()' not in s:
    anchor = '\tselectedOutboundTCP'
    position = s.index(anchor, s.index('type URLTestGroup struct'))
    s = s[:position] + '\ttcpSelection common.TypedValue[groupSelection]\n\tudpSelection common.TypedValue[groupSelection]\n' + s[position:]
    for network, field in [('TCP', 'outboundTCP'), ('UDP', 'outboundUDP')]:
        for value in ['nil', field]:
            anchor = f'g.selectedOutbound{network}.Store({value})'
            if anchor not in s:
                raise RuntimeError('Missing selection anchor: ' + anchor)
            s = s.replace(anchor, f'g.recordSelection(N.Network{network}, {value})\n\t\t' + anchor)
    s += '''
type groupSelection struct {
    Previous string `json:"previous"`
    Current string `json:"current"`
    ChangedAt time.Time `json:"changed_at"`
    Reason string `json:"reason"`
}

func (g *URLTestGroup) recordSelection(network string, selected adapter.Outbound) {
    previous := g.selectedOutboundTCP.Load()
    if network == N.NetworkUDP { previous = g.selectedOutboundUDP.Load() }
    if previous == selected { return }
    state := groupSelection{ChangedAt: time.Now(), Reason: "initial-selection"}
    if previous != nil { state.Previous = previous.Tag() }
    if selected != nil { state.Current = selected.Tag() }
    if selected == nil { state.Reason = "no-compatible-candidate"
    } else if g.strategy == "direct-fallback" && selected == g.outbounds[1] { state.Reason = "control-site-unreachable"
    } else if previous != nil {
        if g.strategy == "direct-fallback" { state.Reason = "direct-recovered"
        } else if _, available := managedURLTestHistory(g, previous, network); !available { state.Reason = "previous-unavailable"
        } else if g.strategy == "priority" { state.Reason = "higher-priority-available"
        } else { state.Reason = "lower-latency" }
    } else if _, available := managedURLTestHistory(g, selected, network); !available { state.Reason = "no-successful-probe" }
    if network == N.NetworkTCP { g.tcpSelection.Store(state) } else { g.udpSelection.Store(state) }
}

func (s *URLTest) SelectionDiagnostics() map[string]any {
    g := s.group
    if g == nil { return map[string]any{"tag": s.Tag(), "state": "not-started"} }
    candidates := make([]map[string]any, 0)
    for index, outbound := range g.outbounds {
        if index >= 64 { break }
        candidate := map[string]any{"tag": outbound.Tag(), "reachable": false}
        if history, available := managedURLTestHistory(g, outbound, N.NetworkTCP); available {
            candidate["reachable"] = true
            candidate["rtt_ms"] = history.Delay
            candidate["checked_at"] = history.Time
        }
        candidates = append(candidates, candidate)
    }
    return map[string]any{"tag": s.Tag(), "strategy": g.strategy, "url": g.link,
        "tcp": g.tcpSelection.Load(), "udp": g.udpSelection.Load(),
        "candidates": candidates, "candidate_count": len(g.outbounds)}
}
'''
    p.write_text(s)
