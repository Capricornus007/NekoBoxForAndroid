package libcore

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestHistoryWindowExpiresWithoutTimer(t *testing.T) {
	now := time.Now()
	d := &connectionDiagnostics{clock: func() time.Time { return now }}
	d.GroupDial(context.Background(), "old")
	now = now.Add(9 * time.Minute)
	for i := 0; i < 500; i++ {
		d.GroupDial(context.Background(), fmt.Sprint(i))
	}
	if len(d.snapshot()) != 501 {
		t.Fatal("history still truncated to a small event count")
	}
	now = now.Add(time.Minute)
	if got := d.snapshot(); len(got) != 500 || got[0]["outbound"] != "0" {
		t.Fatal("ten-minute boundary was not expired")
	}
	now = now.Add(10 * time.Minute)
	if len(d.snapshot()) != 0 {
		t.Fatal("idle history did not expire on read")
	}
}

func TestHistoryPaginationHasNoGapsAndFiltersAcrossPages(t *testing.T) {
	d := &connectionDiagnostics{}
	d.record(map[string]any{"id": "same/1", "uid": int32(1212345), "domain": "example.com"})
	for i := 0; i < 350; i++ {
		d.record(map[string]any{"id": fmt.Sprint(i), "outbound": "other"})
	}
	d.record(map[string]any{"id": "same/1", "kind": "selected", "outbound": "node"})
	d.record(map[string]any{"id": "", "domain": "example.com", "outbound": "node"})
	q := historyQuery{App: "1212345", Domain: "EXAMPLE.com", Outbound: "NODE", Limit: 1}
	p := d.page(q)
	if p["history_total"] != 2 || !p["history_has_more"].(bool) {
		t.Fatalf("correlation lost: %+v", p)
	}
	q.Before = p["history_before"].(int64)
	p = d.page(q)
	if len(p["events"].([]map[string]any)) != 1 || p["history_has_more"].(bool) {
		t.Fatal("older metadata is inaccessible")
	}
	q.App = "12345"
	if d.page(q)["history_total"] != 0 {
		t.Fatal("UID matched a suffix from another Android profile")
	}
	seen := map[int64]bool{}
	q = historyQuery{Limit: 37}
	for {
		p = d.page(q)
		for _, e := range p["events"].([]map[string]any) {
			seq := e["sequence"].(int64)
			if seen[seq] {
				t.Fatal("duplicate page event")
			}
			seen[seq] = true
		}
		if !p["history_has_more"].(bool) {
			break
		}
		q.Before = p["history_before"].(int64)
	}
	if len(seen) != 353 {
		t.Fatalf("page gaps: %d", len(seen))
	}
}

func TestHistoryBurstBoundAndPagePayload(t *testing.T) {
	d := &connectionDiagnostics{}
	for i := 0; i < historyMaxEntries+200; i++ {
		d.GroupDial(context.Background(), strings.Repeat("ж", 512))
	}
	p := d.page(historyQuery{Limit: 200})
	if !p["history_truncated"].(bool) || d.bytes > historyMaxBytes || len(d.snapshot()) > historyMaxEntries {
		t.Fatal("unbounded history or silent eviction")
	}
	size := 0
	for _, e := range p["events"].([]map[string]any) {
		size += len(e["outbound"].(string)) + 256
	}
	if size > historyPageBytes || !p["history_has_more"].(bool) {
		t.Fatal("page exceeds Binder budget")
	}
}
