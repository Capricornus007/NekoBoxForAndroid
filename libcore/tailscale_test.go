package libcore

import (
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/tailscale/ipn"
)

func statusWithPeers(peers ...*adapter.TailscalePeer) *adapter.TailscaleEndpointStatus {
	return &adapter.TailscaleEndpointStatus{
		BackendState: ipn.Running.String(),
		UserGroups:   []*adapter.TailscaleUserGroup{{Peers: peers}},
	}
}

func TestTailscaleReady(t *testing.T) {
	tests := []struct {
		name           string
		status         *adapter.TailscaleEndpointStatus
		exitNodeWanted bool
		want           bool
	}{
		{name: "nil status without exit"},
		{name: "nil status with exit", exitNodeWanted: true},
		{name: "running without requested exit", status: statusWithPeers(), want: true},
		{
			name:           "selected approved live peer without snapshot",
			status:         statusWithPeers(&adapter.TailscalePeer{ExitNode: true, ExitNodeOption: true, Online: true}),
			exitNodeWanted: true, want: true,
		},
		{
			name:           "selection does not require control plane online flag",
			status:         statusWithPeers(&adapter.TailscalePeer{ExitNode: true, ExitNodeOption: true}),
			exitNodeWanted: true, want: true,
		},
		{
			name:           "stale snapshot without live peers",
			status:         &adapter.TailscaleEndpointStatus{BackendState: ipn.Running.String(), ExitNode: &adapter.TailscalePeer{StableID: "removed-exit", Online: true}},
			exitNodeWanted: true,
		},
		{
			name: "stale snapshot with unselected live peer",
			status: &adapter.TailscaleEndpointStatus{
				BackendState: ipn.Running.String(), ExitNode: &adapter.TailscalePeer{StableID: "old-exit", Online: true},
				UserGroups: []*adapter.TailscaleUserGroup{{Peers: []*adapter.TailscalePeer{{StableID: "old-exit", ExitNodeOption: true, Online: true}}}},
			},
			exitNodeWanted: true,
		},
		{name: "nil groups", status: &adapter.TailscaleEndpointStatus{BackendState: ipn.Running.String()}, exitNodeWanted: true},
		{name: "nil peer entry", status: statusWithPeers(nil), exitNodeWanted: true},
		{name: "zero value peer", status: statusWithPeers(&adapter.TailscalePeer{}), exitNodeWanted: true},
		{name: "selected but not eligible", status: statusWithPeers(&adapter.TailscalePeer{ExitNode: true, Online: true}), exitNodeWanted: true},
		{name: "eligible but not selected", status: statusWithPeers(&adapter.TailscalePeer{ExitNodeOption: true, Online: true}), exitNodeWanted: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := tailscaleReady(test.status, test.exitNodeWanted); got != test.want {
				t.Fatalf("tailscaleReady(status, %v) = %v, want %v", test.exitNodeWanted, got, test.want)
			}
		})
	}
}

func TestTailscaleReadyRequiresRunning(t *testing.T) {
	for _, state := range []string{"", ipn.NoState.String(), ipn.NeedsLogin.String(), ipn.NeedsMachineAuth.String(), ipn.Stopped.String(), ipn.Starting.String()} {
		t.Run(state, func(t *testing.T) {
			status := statusWithPeers(&adapter.TailscalePeer{StableID: "exit", ExitNode: true, ExitNodeOption: true, Online: true})
			status.BackendState = state
			status.ExitNode = &adapter.TailscalePeer{StableID: "exit", Online: true}
			for _, exitNodeWanted := range []bool{false, true} {
				if tailscaleReady(status, exitNodeWanted) {
					t.Fatalf("tailscaleReady(status, %v) = true for backend state %q", exitNodeWanted, state)
				}
			}
		})
	}
}
