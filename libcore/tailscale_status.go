package libcore

import (
	"encoding/json"
	"errors"
	"net/netip"
	"sort"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/tailscale/ipn"
	"github.com/sagernet/tailscale/net/tsaddr"
)

const (
	tailscaleMaxPeers       = 256
	tailscaleMaxStatusBytes = 128 * 1024
)

type tailscaleStatusPeer struct {
	ID               string   `json:"id"`
	Name             string   `json:"name"`
	DNSName          string   `json:"dnsName"`
	IPs              []string `json:"ips"`
	Online           bool     `json:"online"`
	Expired          bool     `json:"expired"`
	KeyExpiry        int64    `json:"keyExpiry"`
	ExitNodeOption   bool     `json:"exitNodeOption"`
	ExitNodeSelected bool     `json:"exitNodeSelected"`
}

type tailscaleCurrentExit struct {
	ID   string `json:"id"`
	IP   string `json:"ip"`
	Live bool   `json:"live"`
}

type tailscaleStatusJSON struct {
	BackendState   string                `json:"backendState"`
	NeedsLogin     bool                  `json:"needsLogin"`
	NeedsApproval  bool                  `json:"needsApproval"`
	AuthURL        string                `json:"authUrl"`
	KeyAuth        bool                  `json:"keyAuth"`
	Self           *tailscaleStatusPeer  `json:"self"`
	CurrentExit    *tailscaleCurrentExit `json:"currentExit"`
	Peers          []tailscaleStatusPeer `json:"peers"`
	TotalPeers     int                   `json:"totalPeers"`
	PeersTruncated bool                  `json:"peersTruncated"`
}

func tailscalePeerName(peer *adapter.TailscalePeer) string {
	if peer.HostName != "" {
		return peer.HostName
	}
	return strings.TrimSuffix(peer.DNSName, ".")
}

func canonicalTailscaleIP(raw string) string {
	ip, err := netip.ParseAddr(raw)
	if err != nil || ip.Zone() != "" {
		return ""
	}
	return ip.Unmap().String()
}

func projectTailscalePeer(peer *adapter.TailscalePeer) *tailscaleStatusPeer {
	if peer == nil {
		return nil
	}
	ips := make([]string, 0, len(peer.TailscaleIPs))
	for _, raw := range peer.TailscaleIPs {
		if ip := canonicalTailscaleIP(raw); ip != "" {
			ips = append(ips, ip)
		}
	}
	keyExpiry := peer.KeyExpiry
	if keyExpiry < 0 {
		keyExpiry = 0
	}
	return &tailscaleStatusPeer{
		ID: peer.StableID, Name: tailscalePeerName(peer), DNSName: strings.TrimSuffix(peer.DNSName, "."),
		IPs: ips, Online: peer.Online, Expired: peer.Expired, KeyExpiry: keyExpiry,
		ExitNodeOption: peer.ExitNodeOption, ExitNodeSelected: peer.ExitNode,
	}
}

func nativeTailscalePeers(status *adapter.TailscaleEndpointStatus) []*adapter.TailscalePeer {
	peers := make([]*adapter.TailscalePeer, 0)
	for _, group := range status.UserGroups {
		if group == nil {
			continue
		}
		for _, peer := range group.Peers {
			if peer != nil {
				peers = append(peers, peer)
			}
		}
	}
	return peers
}

func marshalTailscaleStatus(status *adapter.TailscaleEndpointStatus) (string, error) {
	if status == nil {
		return "", errors.New("tailscale:status-unavailable")
	}
	peers := nativeTailscalePeers(status)
	sort.Slice(peers, func(a, b int) bool {
		an, bn := tailscalePeerName(peers[a]), tailscalePeerName(peers[b])
		if an != bn {
			return an < bn
		}
		return peers[a].StableID < peers[b].StableID
	})
	result := tailscaleStatusJSON{
		BackendState:  status.BackendState,
		NeedsLogin:    status.BackendState == ipn.NeedsLogin.String(),
		NeedsApproval: status.BackendState == ipn.NeedsMachineAuth.String(),
		KeyAuth:       status.KeyAuth, Self: projectTailscalePeer(status.Self),
		Peers: make([]tailscaleStatusPeer, 0), TotalPeers: len(peers), PeersTruncated: len(peers) != 0,
	}
	if result.NeedsLogin {
		result.AuthURL = status.AuthURL
	}
	if status.SelectedExitNodeID != "" || status.SelectedExitNodeIP != "" {
		result.CurrentExit = &tailscaleCurrentExit{
			ID: status.SelectedExitNodeID, IP: canonicalTailscaleIP(status.SelectedExitNodeIP),
		}
		for _, peer := range peers {
			matches := peer.StableID == status.SelectedExitNodeID && status.SelectedExitNodeID != ""
			if status.SelectedExitNodeID == "" && result.CurrentExit.IP != "" {
				for _, raw := range peer.TailscaleIPs {
					matches = matches || canonicalTailscaleIP(raw) == result.CurrentExit.IP
				}
			}
			if matches {
				result.CurrentExit.ID = peer.StableID
				if result.CurrentExit.IP == "" {
					result.CurrentExit.IP = preferredTailscalePeerIP(peer)
				}
				result.CurrentExit.Live = peer.ExitNode
				break
			}
		}
	}
	fixed, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	if len(fixed) > tailscaleMaxStatusBytes {
		return "", errors.New("tailscale:status-too-large: fixed fields exceed 128 KiB")
	}
	bytes := len(fixed)
	for _, peer := range peers {
		if len(result.Peers) == tailscaleMaxPeers {
			break
		}
		projected := projectTailscalePeer(peer)
		encoded, err := json.Marshal(projected)
		if err != nil {
			return "", err
		}
		cost := len(encoded)
		if len(result.Peers) != 0 {
			cost++
		}
		// Completing the inventory changes true to false, adding one JSON byte.
		completionByte := 0
		if len(result.Peers)+1 == len(peers) {
			completionByte = 1
		}
		if bytes+cost+completionByte > tailscaleMaxStatusBytes {
			break
		}
		bytes += cost
		result.Peers = append(result.Peers, *projected)
	}
	result.PeersTruncated = len(result.Peers) != len(peers)
	encoded, err := json.Marshal(result)
	return string(encoded), err
}

func preferredTailscalePeerIP(peer *adapter.TailscalePeer) string {
	var selected netip.Addr
	for _, raw := range peer.TailscaleIPs {
		ip, err := netip.ParseAddr(raw)
		if err != nil || ip.Zone() != "" {
			continue
		}
		ip = ip.Unmap()
		if !tsaddr.IsTailscaleIP(ip) {
			continue
		}
		if !selected.IsValid() || (ip.Is4() && selected.Is6()) || (ip.Is4() == selected.Is4() && ip.Less(selected)) {
			selected = ip
		}
	}
	if !selected.IsValid() {
		return ""
	}
	return selected.String()
}
