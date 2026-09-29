package libcore

import (
	"context"
	"net"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

func TestConnectionTrackerCountsClosesAndBoundsHistory(t *testing.T) {
	tracker := newConnectionTracker()
	metadata := adapter.InboundContext{
		Network:              "tcp",
		Inbound:              "tun-in",
		Source:               M.ParseSocksaddr("172.19.0.1:40000"),
		Destination:          M.ParseSocksaddr("example.com:443"),
		DestinationAddresses: []netip.Addr{netip.MustParseAddr("192.0.2.10")},
		ProcessInfo:          &adapter.ConnectionOwner{UserId: 10123, AndroidPackageNames: []string{"org.example.app"}},
	}

	client, server := net.Pipe()
	conn := tracker.RoutedConnection(context.Background(), server, metadata, nil, nil)
	go func() {
		_, _ = client.Write([]byte("hello"))
		_ = client.Close()
	}()
	buf := make([]byte, 16)
	n, _ := conn.Read(buf)
	if n != 5 {
		t.Fatalf("read %d bytes, want 5", n)
	}

	live := tracker.list(false)
	if len(live) != 1 || live[0].Domain != "example.com" || live[0].Package != "org.example.app" || live[0].Upload != 5 || live[0].Addresses[0] != "192.0.2.10" {
		t.Fatalf("unexpected live entry %+v", live)
	}

	// History is off: closing drops the entry entirely.
	_ = conn.Close()
	if entries := tracker.list(true); len(entries) != 0 {
		t.Fatalf("expected no entries without history, got %+v", entries)
	}

	tracker.setHistoryLimit(2)
	for i := 0; i < 3; i++ {
		entry := tracker.open(metadata, "rule", "proxy")
		tracker.close(entry, "finished")
	}
	closed := tracker.list(true)
	if len(closed) != 2 || closed[0].ID != 3 || closed[1].ID != 4 || closed[1].CloseReason != "finished" || closed[1].End == 0 {
		t.Fatalf("history not bounded to the newest two: %+v", closed)
	}
	if len(tracker.list(false)) != 0 {
		t.Fatal("closed entries leaked into the live list")
	}
	tracker.setHistoryLimit(0)
	if len(tracker.list(true)) != 0 {
		t.Fatal("disabling history should drop it")
	}
}
