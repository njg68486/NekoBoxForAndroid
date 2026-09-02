package x365

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2ray"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// TypeX365 is the private protocol name of 365VPN (VLESS-derived handshake).
const TypeX365 = "x365"

// X365OutboundOptions reuses the whole VLESS option surface
// (uuid/flow/encryption/tls/transport/packet_encoding ...).
type X365OutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	UUID       string      `json:"uuid"`
	Flow       string      `json:"flow,omitempty"`
	Encryption string      `json:"encryption,omitempty"`
	Network    option.NetworkList `json:"network,omitempty"`
	option.OutboundTLSOptionsContainer
	Multiplex      *option.OutboundMultiplexOptions `json:"multiplex,omitempty"`
	Transport      *option.V2RayTransportOptions    `json:"transport,omitempty"`
	PacketEncoding *string                          `json:"packet_encoding,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[X365OutboundOptions](registry, TypeX365, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger     logger.ContextLogger
	dialer     N.Dialer
	client     *Client
	serverAddr M.Socksaddr
	tlsConfig  tls.Config
	transport  adapter.V2RayClientTransport
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options X365OutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	ob := &Outbound{
		Adapter:    outbound.NewAdapterWithDialerOptions(TypeX365, tag, options.Network.Build(), options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		serverAddr: options.ServerOptions.Build(),
	}
	if options.TLS != nil {
		ob.tlsConfig, err = tls.NewClient(ctx, options.Server, common.PtrValueOrDefault(options.TLS))
		if err != nil {
			return nil, err
		}
	}
	if options.Transport != nil {
		ob.transport, err = v2ray.NewClientTransport(ctx, ob.dialer, ob.serverAddr, common.PtrValueOrDefault(options.Transport), ob.tlsConfig)
		if err != nil {
			return nil, E.Cause(err, "create client transport: ", options.Transport.Type)
		}
	}
	ob.client, err = NewClient(options.UUID)
	if err != nil {
		return nil, err
	}
	return ob, nil
}

func (h *Outbound) dialServer(ctx context.Context) (net.Conn, error) {
	if h.transport != nil {
		return h.transport.DialContext(ctx)
	}
	conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	if h.tlsConfig != nil {
		conn, err = tls.ClientHandshake(ctx, conn, h.tlsConfig)
		if err != nil {
			common.Close(conn)
			return nil, err
		}
	}
	return conn, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	conn, err := h.dialServer(ctx)
	if err != nil {
		return nil, err
	}
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
		return h.client.DialEarlyConn(conn, destination), nil
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
		return h.client.DialEarlyPacketConn(conn, destination), nil
	default:
		common.Close(conn)
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	conn, err := h.dialServer(ctx)
	if err != nil {
		return nil, err
	}
	return h.client.DialEarlyPacketConn(conn, destination), nil
}

func (h *Outbound) InterfaceUpdated() {
	if h.transport != nil {
		h.transport.Close()
	}
}

func (h *Outbound) Close() error {
	return common.Close(h.transport)
}
