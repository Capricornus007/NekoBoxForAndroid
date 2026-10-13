//go:build !with_openvpn

package libcore

import (
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/dns"
)

func nekoboxAndroidOpenVPNEndpoint(*endpoint.Registry) {}

func nekoboxAndroidOpenVPNDNSTransport(*dns.TransportRegistry) {}
