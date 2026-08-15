package shadowquic

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/exclavenetwork/sing-shadowquic"
)

const TypeShadowQUIC = "shadowquic"

type ShadowQUICOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	Username          string `json:"username,omitempty"`
	Password          string `json:"password,omitempty"`
	CongestionControl string `json:"congestion_control,omitempty" enum:"bbr,cubic,new_reno"`
	UDPOverStream     bool   `json:"udp_over_stream,omitempty"`
	ZeroRTTHandshake  bool   `json:"zero_rtt_handshake,omitempty"`
	SunnyQUIC         bool   `json:"sunny_quic,omitempty"`
	// Overrides the ServerName/ALPN used in the QUIC TLS handshake when set.
	ServerName string   `json:"server_name,omitempty"`
	Alpn       []string `json:"alpn,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[ShadowQUICOutboundOptions](registry, TypeShadowQUIC, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger logger.ContextLogger
	client *shadowquic.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options ShadowQUICOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: options.ServerIsDomain(),
	})
	if err != nil {
		return nil, err
	}
	if options.ServerName == "" {
		options.ServerName = options.Server
	}
	if len(options.Alpn) == 0 {
		options.Alpn = []string{"h3"}
	}
	client, err := shadowquic.NewClient(shadowquic.ClientOptions{
		Context:           ctx,
		Dialer:            outboundDialer,
		ServerAddress:     options.Build(),
		Username:          options.Username,
		Password:          options.Password,
		ServerName:        options.ServerName,
		NextProtos:        options.Alpn,
		CongestionControl: options.CongestionControl,
		UDPOverStream:     options.UDPOverStream,
		ZeroRTTHandshake:  options.ZeroRTTHandshake,
		SunnyQUIC:         options.SunnyQUIC,
	})
	if err != nil {
		return nil, err
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(TypeShadowQUIC, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:  logger,
		client:  client,
	}, nil
}

func (o *Outbound) Close() error {
	return o.client.Close()
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch network {
	case N.NetworkTCP:
		ctx, metadata := adapter.ExtendContext(ctx)
		metadata.Outbound = o.Tag()
		metadata.Destination = destination
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		return o.client.DialConn(ctx, destination)
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

func (o *Outbound) CloseWithError(err error) error {
	_ = o.client.Close()
	return err
}