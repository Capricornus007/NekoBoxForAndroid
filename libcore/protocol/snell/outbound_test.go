package snell

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

// TestSnellRegistration guards the API bridge between this outbound and
// Capricornus007/sing-snell: if the library's ClientOptions or the sing-box
// adapter surface drifts (e.g. InterfaceUpdated signature changes), the
// construction path here breaks before CI's gomobile build does.
func TestSnellRegistration(t *testing.T) {
	registry := outbound.NewRegistry()
	RegisterOutbound(registry)

	ctx := context.Background()
	logger := log.NewNOPFactory().NewLogger("test")
	v4Opts := SnellOutboundOptions{
		ServerOptions: option.ServerOptions{
			Server:     "127.0.0.1",
			ServerPort: 10443,
		},
		PSK:      "test-password-psk",
		Version:  4,
		ObfsMode: "http",
		ObfsHost: "example.com",
		Reuse:    true,
	}

	ob4, err := NewOutbound(ctx, nil, logger, "snell-v4-test", v4Opts)
	if err != nil {
		t.Fatalf("NewOutbound Snell v4 failed: %v", err)
	}
	if ob4.Tag() != "snell-v4-test" {
		t.Fatalf("expected tag snell-v4-test, got %s", ob4.Tag())
	}
	if ob4.Type() != TypeSnell {
		t.Fatalf("expected type snell, got %s", ob4.Type())
	}

	v6Opts := SnellOutboundOptions{
		ServerOptions: option.ServerOptions{
			Server:     "127.0.0.1",
			ServerPort: 10443,
		},
		PSK:     "test-password-psk-123",
		Version: 6,
		Mode:    "default",
		Reuse:   true,
	}

	ob6, err := NewOutbound(ctx, nil, logger, "snell-v6-test", v6Opts)
	if err != nil {
		t.Fatalf("NewOutbound Snell v6 failed: %v", err)
	}
	if ob6.Tag() != "snell-v6-test" {
		t.Fatalf("expected tag snell-v6-test, got %s", ob6.Tag())
	}
	if ob6.Type() != TypeSnell {
		t.Fatalf("expected type snell, got %s", ob6.Type())
	}
}
