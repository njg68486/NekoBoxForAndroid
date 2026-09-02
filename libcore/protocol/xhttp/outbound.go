package xhttp

// Heysocks (黑石 VPN) OFFICIAL "xhttp" private protocol — sing-box outbound.
//
// The real wire is BARE TCP carrying the xstream camouflage + AES-128-CTR
// stream. There is NO real TLS ClientHello and NO real HTTP POST — the
// tls/servername/xhttp-opts fields in the official app config are decoy
// metadata only.
//
// The dial target is the AUTH GATEWAY (`gateway` field, host:port), NOT the
// landing server/port (those are display-only in the official config).

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

// TypeXhttp is the official protocol name.
const TypeXhttp = "xhttp"

// XhttpOutboundOptions carries the private protocol parameters.
// NOTE: does NOT embed option.ServerOptions (uuid field name collision is
// avoided by declaring server/server_port manually); uuid lives at top level.
type XhttpOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	// Password is the credential "key1[:key2]". The AES key derives solely
	// from key1's ASCII string, fixed across nodes.
	Password string `json:"password,omitempty"`
	Network  option.NetworkList `json:"network,omitempty"`
	// Sess is the 68-byte session prelude (hex), server-issued auth material.
	Sess string `json:"sess,omitempty"`
	// Auth is the runtime auth token (hex) validated by the server. REQUIRED.
	Auth string `json:"auth,omitempty"`
	// UUID is optional decoy metadata from the official config.
	UUID string `json:"uuid,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[XhttpOutboundOptions](registry, TypeXhttp, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger     logger.ContextLogger
	dialer     N.Dialer
	serverAddr M.Socksaddr
	cipher     *StreamCipher
	sess       []byte
	token      []byte
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options XhttpOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	cipher, err := NewStreamCipher(options.Password)
	if err != nil {
		return nil, E.Cause(err, "xhttp: init cipher")
	}
	sess := decodeHex(options.Sess)
	if len(sess) == 0 && options.Sess != "" {
		return nil, E.New("xhttp: invalid sess hex")
	}
	token := decodeHex(options.Auth)
	if len(token) == 0 && options.Auth != "" {
		return nil, E.New("xhttp: invalid auth hex")
	}
	ob := &Outbound{
		Adapter:    outbound.NewAdapterWithDialerOptions(TypeXhttp, tag, options.Network.Build(), options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		serverAddr: options.ServerOptions.Build(),
		cipher:     cipher,
		sess:       sess,
		token:      token,
	}
	return ob, nil
}

func (o *Outbound) Close() error      { return nil }
func (o *Outbound) InterfaceUpdated() {}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		base, err := o.dialer.DialContext(ctx, N.NetworkTCP, o.serverAddr)
		if err != nil {
			return nil, err
		}
		return o.cipher.ClientConn(base, destination, o.sess, o.token), nil
	default:
		return nil, E.New("unsupported network: ", network)
	}
}

// ListenPacket tunnels UDP-over-TCP (uot v2) inside the same xstream
// cipher — same pattern as the sing uot.Client.
func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	o.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	base, err := o.dialer.DialContext(ctx, N.NetworkTCP, o.serverAddr)
	if err != nil {
		return nil, err
	}
	uConn, err := (&uot.Client{Version: 2}).DialEarlyConn(
		o.cipher.ClientConn(base, uot.RequestDestination(2), o.sess, o.token),
		false, destination,
	)
	if err != nil {
		common.Close(base)
		return nil, err
	}
	return uConn, nil
}
