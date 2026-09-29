package libcore

import (
	"errors"
	"syscall"
	"testing"
)

func TestIcmpPingLoopback(t *testing.T) {
	latency, err := IcmpPing("127.0.0.1", 2000)
	if err != nil {
		// Linux keeps unprivileged ICMP sockets behind net.ipv4.ping_group_range.
		if errors.Is(err, syscall.EACCES) || errors.Is(err, syscall.EPERM) {
			t.Skip("unprivileged ICMP sockets disabled:", err)
		}
		t.Fatal(err)
	}
	if latency < 0 {
		t.Fatalf("negative latency %d", latency)
	}
}

func TestIcmpPingRejectsHostnames(t *testing.T) {
	if _, err := IcmpPing("localhost", 100); err == nil {
		t.Fatal("hostname accepted")
	}
}
