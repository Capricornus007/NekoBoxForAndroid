package libcore

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"sync"
	"time"
)

const historyWindow = 10 * time.Minute
const historyMaxEntries = 16384
const historyMaxBytes = 8 * 1024 * 1024
const historyPageBytes = 96 * 1024 // Also bounds the UTF-16 Binder response.

type diagnosticRecord struct {
	at       time.Time
	sequence int64
	fields   map[string]any
	bytes    int
}

// Debug/trace only. Expiration is lazy: recording/reading needs no cleanup timer.
// Count/byte ceilings protect memory during bursts; truncation is explicit.
type connectionDiagnostics struct {
	access      sync.Mutex
	entries     []diagnosticRecord
	head, bytes int
	sequence    int64
	truncatedAt time.Time
	clock       func() time.Time
}

func (d *connectionDiagnostics) now() time.Time {
	if d.clock != nil {
		return d.clock()
	}
	return time.Now()
}

func (d *connectionDiagnostics) prune(now time.Time) {
	cutoff := now.Add(-historyWindow)
	for d.head < len(d.entries) {
		old := d.entries[d.head]
		expired := !old.at.After(cutoff)
		if !expired && len(d.entries)-d.head <= historyMaxEntries && d.bytes <= historyMaxBytes {
			break
		}
		if !expired {
			d.truncatedAt = old.at
		}
		d.bytes -= old.bytes
		d.entries[d.head] = diagnosticRecord{}
		d.head++
	}
	if d.head > 1024 && d.head*2 >= len(d.entries) {
		d.entries = append([]diagnosticRecord(nil), d.entries[d.head:]...)
		d.head = 0
	}
}

func (d *connectionDiagnostics) record(fields map[string]any) {
	d.access.Lock()
	defer d.access.Unlock()
	now := d.now()
	d.sequence++
	fields["time"] = now.UTC().Format(time.RFC3339Nano)
	fields["sequence"] = d.sequence
	raw, err := json.Marshal(fields)
	if err != nil {
		return
	}
	size := len(raw) + 256
	d.entries = append(d.entries, diagnosticRecord{now, d.sequence, fields, size})
	d.bytes += size
	d.prune(now)
}

func (d *connectionDiagnostics) snapshot() []map[string]any {
	d.access.Lock()
	defer d.access.Unlock()
	d.prune(d.now())
	result := make([]map[string]any, 0, len(d.entries)-d.head)
	for _, entry := range d.entries[d.head:] {
		result = append(result, entry.fields)
	}
	return result
}

type historyQuery struct {
	App      string `json:"app"`
	Domain   string `json:"domain"`
	Outbound string `json:"outbound"`
	Before   int64  `json:"before"`
	Limit    int    `json:"limit"`
}

func historyContains(value any, query string) bool {
	return value != nil && strings.Contains(strings.ToLower(fmt.Sprint(value)), query)
}

func (d *connectionDiagnostics) page(query historyQuery) map[string]any {
	d.access.Lock()
	defer d.access.Unlock()
	now := d.now()
	d.prune(now)
	entries := d.entries[d.head:]
	app, domain, outbound := strings.ToLower(strings.TrimSpace(query.App)), strings.ToLower(strings.TrimSpace(query.Domain)), strings.ToLower(strings.TrimSpace(query.Outbound))
	numericUID := app != "" && strings.IndexFunc(app, func(r rune) bool { return r < '0' || r > '9' }) == -1
	// A full connection ID joins events. Independent DNS never joins by domain;
	// pagination does not cut off the metadata used for filtering.
	type key struct {
		id       string
		sequence int64
	}
	keyFor := func(entry diagnosticRecord) key {
		id, _ := entry.fields["id"].(string)
		if id != "" {
			return key{id: id}
		}
		return key{sequence: entry.sequence}
	}
	matched := make(map[key]uint8)
	for _, entry := range entries {
		fields := entry.fields
		var flags uint8
		if app == "" || (numericUID && fmt.Sprint(fields["uid"]) == app) || (!numericUID && (historyContains(fields["uid"], app) || historyContains(fields["packages"], app))) {
			flags |= 1
		}
		if domain == "" || historyContains(fields["domain"], domain) || historyContains(fields["destination"], domain) {
			flags |= 2
		}
		if outbound == "" || historyContains(fields["outbound"], outbound) {
			flags |= 4
		}
		matched[keyFor(entry)] |= flags
	}
	limit := max(0, min(query.Limit, 200))
	events := make([]map[string]any, 0, limit)
	var before int64
	total, bytes := 0, 0
	more, full := false, false
	for i := len(entries) - 1; i >= 0; i-- {
		entry := entries[i]
		if matched[keyFor(entry)] != 7 {
			continue
		}
		total++
		if query.Before > 0 && entry.sequence >= query.Before {
			continue
		}
		if full || len(events) >= limit || bytes+entry.bytes > historyPageBytes {
			more, full = true, true
			continue
		}
		events = append(events, entry.fields)
		bytes += entry.bytes
		before = entry.sequence
	}
	slices.Reverse(events)
	return map[string]any{"events": events, "history_total": total, "history_retained": len(entries), "history_before": before, "history_has_more": more,
		"history_window_seconds": int(historyWindow.Seconds()), "history_truncated": d.truncatedAt.After(now.Add(-historyWindow))}
}
