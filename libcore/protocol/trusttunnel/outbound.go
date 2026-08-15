package trusttunnel

import (
	"context"
	"net"

	"github.com/xchacha20-poly1305/sing-trusttunnel"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const TypeTrustTunnel = "trusttunnel"

type TrustTunnelOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	option.OutboundTLSOptionsContainer
	Username              string `json:"username,omitempty"`
	Password              string `json:"password,omitempty"`
	QUIC                  bool   `json:"quic,omitempty"`
	QUICCongestionControl string `json:"quic_congestion_control,omitempty" enum:"bbr,cubic,new_reno"`
	HealthCheck           bool   `json:"health_check,omitempty"`
	// Overrides the user-agents used to distinguish TCP/UDP/ICMP/health-check.
	TCPUserAgent          string `json:"tcp_user_agent,omitempty"`
	UDPUserAgent          string `json:"udp_user_agent,omitempty"`
	ICMPUserAgent         string `json:"icmp_user_agent,omitempty"`
	HealthCheckUserAgent  string `json:"health_check_user_agent,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[TrustTunnelOutboundOptions](registry, TypeTrustTunnel, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger logger.ContextLogger
	client *trusttunnel.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options TrustTunnelOutboundOptions) (adapter.Outbound, error) {
	if options.TLS == nil || !options.TLS.Enabled {
		return nil, C.ErrTLSRequired
	}
	tlsConfig, err := tls.NewClient(ctx, logger, options.Server, common.PtrValueOrDefault(options.TLS))
	if err != nil {
		return nil, err
	}
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	if options.Username == "" {
		return nil, E.New("missing username")
	}
	userAgent := trusttunnel.NewUserAgentFromAppName("sing-trusttunnel")
	if options.TCPUserAgent != "" {
		userAgent.TCPUserAgent = options.TCPUserAgent
	}
	if options.UDPUserAgent != "" {
		userAgent.UDPUserAgent = options.UDPUserAgent
	}
	if options.ICMPUserAgent != "" {
		userAgent.ICMPUserAgent = options.ICMPUserAgent
	}
	if options.HealthCheckUserAgent != "" {
		userAgent.HealthCheckUserAgent = options.HealthCheckUserAgent
	}
	client, err := trusttunnel.NewClient(trusttunnel.ClientOptions{
		Ctx:                   ctx,
		Detour:                outboundDialer,
		Server:                options.Build(),
		Auth:                  auth.User{Username: options.Username, Password: options.Password},
		TLSConfig:             tlsConfig,
		QUIC:                  options.QUIC,
		QUICCongestionControl: options.QUICCongestionControl,
		HealthCheck:           options.HealthCheck,
		UserAgents:            userAgent,
	})
	if err != nil {
		return nil, err
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(TypeTrustTunnel, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:  logger,
		client:  client,
	}, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch network {
	case N.NetworkTCP:
		ctx, metadata := adapter.ExtendContext(ctx)
		metadata.Outbound = o.Tag()
		metadata.Destination = destination
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		return o.client.Dial(ctx, destination)
	case N.NetworkUDP:
		conn, err := o.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(conn, destination), nil
	default:
		return nil, E.New("unsupported network: ", network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	o.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	return o.client.ListenPacket(ctx)
}

func (o *Outbound) Close() error {
	return o.client.Close()
}

func (o *Outbound) CloseWithError(err error) error {
	_ = o.client.Close()
	return err
}