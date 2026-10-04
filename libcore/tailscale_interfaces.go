package libcore

import (
	"encoding/json"
	"net"
	"net/netip"

	"github.com/sagernet/tailscale/net/netmon"
)

type platformNetworkInterface struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Multicast    bool     `json:"multicast"`
	Addresses    []string `json:"addresses"`
}

// Android 11+ denies apps the netlink bind behind net.Interfaces, and tsnet fails to start
// without an interface list. The core installs its own getter only for platforms that supply
// network interfaces, and clears the getter when a Tailscale endpoint closes, so the list from
// Java is registered again before every start.
func registerTailscaleInterfaces() {
	if intfBox != nil {
		netmon.RegisterInterfaceGetter(platformInterfaces)
	}
}

func platformInterfaces() ([]netmon.Interface, error) {
	content, err := intfBox.NetworkInterfaces()
	if err != nil {
		return nil, err
	}
	var list []platformNetworkInterface
	if err := json.Unmarshal([]byte(content), &list); err != nil {
		return nil, err
	}
	interfaces := make([]netmon.Interface, 0, len(list))
	for _, it := range list {
		var flags net.Flags
		if it.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if it.Loopback {
			flags |= net.FlagLoopback
		}
		if it.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if it.Multicast {
			flags |= net.FlagMulticast
		}
		addresses := make([]net.Addr, 0, len(it.Addresses))
		for _, address := range it.Addresses {
			prefix, err := netip.ParsePrefix(address)
			if err != nil {
				continue
			}
			addresses = append(addresses, &net.IPNet{
				IP:   prefix.Addr().AsSlice(),
				Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen()),
			})
		}
		interfaces = append(interfaces, netmon.Interface{
			Interface: &net.Interface{Index: it.Index, MTU: it.MTU, Name: it.Name, Flags: flags},
			AltAddrs:  addresses,
		})
	}
	return interfaces, nil
}
