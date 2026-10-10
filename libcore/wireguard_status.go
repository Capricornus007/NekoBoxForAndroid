package libcore

import (
	"encoding/json"
	"errors"

	"github.com/sagernet/sing-box/transport/amneziawg"
)

type wireGuardPeerStatus struct {
	PublicKey string `json:"publicKey"`
	Endpoint  string `json:"endpoint"`
	// Unix milliseconds of the last completed handshake; 0 until the first one.
	LastHandshake       int64    `json:"lastHandshake"`
	RxBytes             uint64   `json:"rxBytes"`
	TxBytes             uint64   `json:"txBytes"`
	PersistentKeepalive uint16   `json:"persistentKeepalive"`
	AllowedIPs          []string `json:"allowedIPs"`
}

type wireGuardStatusJSON struct {
	Peers []wireGuardPeerStatus `json:"peers"`
}

type wireGuardStatusSource interface {
	PeerStatus() ([]amneziawg.PeerStatus, error)
}

// WireGuardStatus returns the peer state of a running WireGuard or AmneziaWG outbound as JSON.
// It carries no key material other than the peers' public keys.
func WireGuardStatus(i *BoxInstance, tag string) (result string, err error) {
	defer deferPanicToError("box.WireGuardStatus", func(err_ error) { err = err_ })
	if i == nil {
		return "", errors.New("wireguard:not-running")
	}
	// The read is short and stays in memory; holding access keeps Close from tearing the
	// device down while it runs.
	i.access.Lock()
	defer i.access.Unlock()
	if !i.running || i.Box == nil {
		return "", errors.New("wireguard:not-running")
	}
	outbound, loaded := i.Box.Outbound().Outbound(tag)
	source, isWireGuard := outbound.(wireGuardStatusSource)
	if !loaded || !isWireGuard {
		return "", errors.New("wireguard:not-found")
	}
	peers, err := source.PeerStatus()
	if err != nil {
		return "", errors.New("wireguard:unavailable: " + err.Error())
	}
	status := wireGuardStatusJSON{Peers: make([]wireGuardPeerStatus, 0, len(peers))}
	for _, peer := range peers {
		projected := wireGuardPeerStatus{
			PublicKey:           peer.PublicKey,
			Endpoint:            peer.Endpoint,
			RxBytes:             peer.RxBytes,
			TxBytes:             peer.TxBytes,
			PersistentKeepalive: peer.PersistentKeepaliveInterval,
			AllowedIPs:          make([]string, 0, len(peer.AllowedIPs)),
		}
		if !peer.LastHandshake.IsZero() {
			projected.LastHandshake = peer.LastHandshake.UnixMilli()
		}
		for _, prefix := range peer.AllowedIPs {
			projected.AllowedIPs = append(projected.AllowedIPs, prefix.String())
		}
		status.Peers = append(status.Peers, projected)
	}
	content, err := json.Marshal(status)
	if err != nil {
		return "", err
	}
	return string(content), nil
}
