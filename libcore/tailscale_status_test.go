package libcore

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/tailscale/ipn"
)

func decodeTailscaleStatus(t *testing.T, status *adapter.TailscaleEndpointStatus) (string, tailscaleStatusJSON) {
	t.Helper()
	raw, err := marshalTailscaleStatus(status)
	if err != nil {
		t.Fatal(err)
	}
	var result tailscaleStatusJSON
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	if len(raw) > tailscaleMaxStatusBytes {
		t.Fatal("status exceeds byte limit")
	}
	return raw, result
}

func TestTailscaleStatusProjection(t *testing.T) {
	status := statusWithPeers(nil,
		&adapter.TailscalePeer{StableID: "b", HostName: "same", DNSName: "same.example.", TailscaleIPs: []string{"fd7a:115c:a1e0:0::2", "100.64.0.2"}, ExitNode: true, ExitNodeOption: true, Online: true},
		&adapter.TailscalePeer{StableID: "a", HostName: "same", Expired: true, KeyExpiry: -1},
	)
	status.Self = &adapter.TailscalePeer{StableID: "self", KeyExpiry: 1800000000, SSHHostKeys: []string{"secret-ssh"}}
	status.UserGroups = append(status.UserGroups, nil)
	status.UserGroups[0].ProfilePicURL = "secret-picture"
	status.UserGroups[0].LoginName = "secret-account"
	status.AuthURL = "https://login.example/secret-token"
	status.KeyAuth = true
	status.SelectedExitNodeID = "b"
	_, result := decodeTailscaleStatus(t, status)
	if result.NeedsLogin || result.NeedsApproval || result.AuthURL != "" || !result.KeyAuth {
		t.Fatal("incorrect running authentication projection")
	}
	if result.TotalPeers != 2 || result.PeersTruncated || result.Peers[0].ID != "a" || result.Peers[1].ID != "b" {
		t.Fatal("inventory is not complete and deterministically sorted")
	}
	if result.Peers[0].KeyExpiry != 0 || !result.Peers[0].Expired || result.Self.KeyExpiry != 1800000000 {
		t.Fatal("expiry projection")
	}
	if result.Peers[1].DNSName != "same.example" || result.Peers[1].IPs[0] != "fd7a:115c:a1e0::2" {
		t.Fatal("name/address normalization")
	}
	if result.CurrentExit == nil || result.CurrentExit.ID != "b" || result.CurrentExit.IP != "100.64.0.2" || !result.CurrentExit.Live {
		t.Fatal("live preference selection")
	}
	first, _ := decodeTailscaleStatus(t, status)
	for n := 0; n < 5; n++ {
		next, _ := decodeTailscaleStatus(t, status)
		if first != next {
			t.Fatal("nondeterministic output")
		}
	}
	for _, secret := range []string{"secret-ssh", "secret-picture", "secret-account", "secret-token", "SSHHostKeys", "ProfilePicURL"} {
		if strings.Contains(first, secret) {
			t.Fatal("private field leaked")
		}
	}
}

func TestTailscaleStatusAuthAndEmptyInventory(t *testing.T) {
	for _, state := range []string{ipn.NeedsLogin.String(), ipn.NeedsMachineAuth.String(), "FutureState"} {
		status := &adapter.TailscaleEndpointStatus{BackendState: state, AuthURL: "https://login.example/" + strings.Repeat("x", 4096)}
		raw, result := decodeTailscaleStatus(t, status)
		if result.BackendState != state || result.NeedsLogin != (state == ipn.NeedsLogin.String()) || result.NeedsApproval != (state == ipn.NeedsMachineAuth.String()) {
			t.Fatal("backend state projection")
		}
		if result.NeedsLogin && result.AuthURL != status.AuthURL {
			t.Fatal("auth URL was truncated")
		}
		if !strings.Contains(raw, `"peers":[]`) || result.Self != nil || result.CurrentExit != nil || result.TotalPeers != 0 || result.PeersTruncated {
			t.Fatal("empty inventory schema")
		}
	}
}

func TestTailscaleStatusCurrentExitIgnoresCachedFallback(t *testing.T) {
	status := statusWithPeers(&adapter.TailscalePeer{StableID: "old", ExitNode: true, TailscaleIPs: []string{"100.64.0.1"}})
	status.ExitNode = &adapter.TailscalePeer{StableID: "old", ExitNode: true}
	_, result := decodeTailscaleStatus(t, status)
	if result.CurrentExit != nil {
		t.Fatal("clear resurrected cached or live old exit")
	}
	status.SelectedExitNodeID = "removed"
	status.SelectedExitNodeIP = "100.64.0.2"
	_, result = decodeTailscaleStatus(t, status)
	if result.CurrentExit == nil || result.CurrentExit.ID != "removed" || result.CurrentExit.Live {
		t.Fatal("removed preference target incorrectly reported live")
	}
	status.SelectedExitNodeID = ""
	status.SelectedExitNodeIP = "100.64.0.1"
	_, result = decodeTailscaleStatus(t, status)
	if result.CurrentExit.ID != "old" || !result.CurrentExit.Live {
		t.Fatal("IP preference target not resolved")
	}
}

func TestTailscaleStatusExactByteBoundary(t *testing.T) {
	status := &adapter.TailscaleEndpointStatus{BackendState: ipn.NeedsLogin.String()}
	base, _ := decodeTailscaleStatus(t, status)
	status.AuthURL = strings.Repeat("x", tailscaleMaxStatusBytes-len(base))
	raw, result := decodeTailscaleStatus(t, status)
	if len(raw) != tailscaleMaxStatusBytes || result.AuthURL != status.AuthURL {
		t.Fatal("exact-bound fixed fields were altered")
	}
	status.AuthURL += "x"
	if _, err := marshalTailscaleStatus(status); err == nil {
		t.Fatal("fixed fields above the exact bound were accepted")
	}
}

func TestTailscaleStatusBoundsAndFullInventoryResolution(t *testing.T) {
	status := statusWithPeers()
	for n := 0; n < 300; n++ {
		status.UserGroups[0].Peers = append(status.UserGroups[0].Peers, &adapter.TailscalePeer{
			StableID: fmt.Sprintf("peer-%03d", n), HostName: fmt.Sprintf("host-%03d", n), TailscaleIPs: []string{"100.64.0.3"},
		})
	}
	_, result := decodeTailscaleStatus(t, status)
	if len(result.Peers) != 256 || result.TotalPeers != 300 || !result.PeersTruncated {
		t.Fatal("peer cap not explicit")
	}
	if ip, err := resolveTailscalePingPeer(status, "peer-299"); err != nil || ip != "100.64.0.3" {
		t.Fatal("truncated inventory incorrectly limits operations")
	}
	status.UserGroups[0].Peers[0].HostName = strings.Repeat("a", tailscaleMaxStatusBytes)
	_, result = decodeTailscaleStatus(t, status)
	if !result.PeersTruncated || len(result.Peers) != 0 || result.TotalPeers != 300 {
		t.Fatal("oversized peer must truncate with explicit total")
	}
	status.BackendState = ipn.NeedsLogin.String()
	status.AuthURL = strings.Repeat("x", tailscaleMaxStatusBytes)
	if _, err := marshalTailscaleStatus(status); err == nil {
		t.Fatal("oversized auth URL must error, not truncate")
	}
	status.AuthURL = ""
	status.Self = &adapter.TailscalePeer{HostName: strings.Repeat("x", tailscaleMaxStatusBytes)}
	if _, err := marshalTailscaleStatus(status); err == nil {
		t.Fatal("oversized self must error")
	}
}
