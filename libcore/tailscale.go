package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"runtime/debug"
	"sort"
	"strings"
	"time"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/protocol/tailscale"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/tailscale/ipn"
	"github.com/sagernet/tailscale/ipn/ipnstate"
)

// deferPanicToError converts a panic into an error via onError; helper ported from hawkff's
// libcore（本地原本沒有此 helper）。
func deferPanicToError(name string, onError func(error)) {
	if r := recover(); r != nil {
		onError(fmt.Errorf("%s panic: %s\n%s", name, r, debug.Stack()))
	}
}

// UrlTestOutbound measures the connection test URL through one outbound or endpoint of a
// running instance instead of its default outbound, so a profile the service already runs
// can be tested without starting a second core.
func UrlTestOutbound(i *BoxInstance, tag string, link string, timeout int32) (latency int32, err error) {
	defer deferPanicToError("box.UrlTestOutbound", func(err_ error) { err = err_ })
	if i == nil {
		return 0, E.New("no instance")
	}
	if _, loaded := i.Box.Outbound().Outbound(tag); !loaded {
		return 0, E.New("outbound not found: ", tag)
	}
	detour := dialer.NewDetour(i.Box.Outbound(), tag, true)
	client := &http.Client{
		Transport: &http.Transport{
			TLSHandshakeTimeout:   3 * time.Second,
			ResponseHeaderTimeout: 3 * time.Second,
			DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				return detour.DialContext(ctx, network, M.ParseSocksaddr(address))
			},
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	return speedtest.UrlTest(ctx, client, link, speedtest.UrlTestStandard_RTT)
}

func tailscaleEndpoint(i *BoxInstance, tag string) (*tailscale.Endpoint, error) {
	if i == nil {
		return nil, E.New("no instance")
	}
	endpoint, loaded := i.Box.Endpoint().Get(tag)
	if !loaded {
		return nil, E.New("endpoint not found: ", tag)
	}
	ts, isTailscale := endpoint.(*tailscale.Endpoint)
	if !isTailscale {
		return nil, E.New("not a Tailscale endpoint: ", tag)
	}
	return ts, nil
}

func tailscaleStatus(ctx context.Context, i *BoxInstance, tag string) (*ipnstate.Status, error) {
	endpoint, err := tailscaleEndpoint(i, tag)
	if err != nil {
		return nil, err
	}
	client, err := endpoint.Server().LocalClient()
	if err != nil {
		return nil, err
	}
	return client.Status(ctx)
}

// TailscaleWaitReady blocks until the node is running, and its exit node is selected when
// the profile configures one, or the timeout passes. Login state is reported so the caller
// can show why a node never came up.
func TailscaleWaitReady(i *BoxInstance, tag string, exitNodeWanted bool, timeoutMs int32) (err error) {
	defer deferPanicToError("box.TailscaleWaitReady", func(err_ error) { err = err_ })
	if _, err = tailscaleEndpoint(i, tag); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	lastState := ""
	for {
		status, statusErr := tailscaleStatus(ctx, i, tag)
		if statusErr == nil {
			lastState = status.BackendState
			if status.BackendState == ipn.Running.String() && (!exitNodeWanted || status.ExitNodeStatus != nil) {
				return nil
			}
			if status.BackendState == ipn.NeedsLogin.String() && status.AuthURL != "" {
				return E.New("Tailscale needs login: ", status.AuthURL)
			}
		}
		select {
		case <-ctx.Done():
			if exitNodeWanted && lastState == ipn.Running.String() {
				return E.New("Tailscale exit node not available")
			}
			return E.New("Tailscale not ready: ", lastState)
		case <-time.After(250 * time.Millisecond):
		}
	}
}

// TailscaleAuthURL returns the pending interactive login URL, or an empty string when the node
// is not waiting for a login.
func TailscaleAuthURL(i *BoxInstance, tag string) (result string, err error) {
	defer deferPanicToError("box.TailscaleAuthURL", func(err_ error) { err = err_ })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := tailscaleStatus(ctx, i, tag)
	if err != nil {
		return "", err
	}
	if status.BackendState != ipn.NeedsLogin.String() {
		return "", nil
	}
	return status.AuthURL, nil
}

type tailscalePeer struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	DNSName  string   `json:"dnsName"`
	IPs      []string `json:"ips"`
	Online   bool     `json:"online"`
	ExitNode bool     `json:"exitNode"`
}

// TailscalePeers lists the node's peers as JSON, sorted by name; exitNode marks peers that
// offer themselves as an exit node and have it approved.
func TailscalePeers(i *BoxInstance, tag string) (result string, err error) {
	defer deferPanicToError("box.TailscalePeers", func(err_ error) { err = err_ })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := tailscaleStatus(ctx, i, tag)
	if err != nil {
		return "", err
	}
	peers := make([]tailscalePeer, 0, len(status.Peer))
	for _, peer := range status.Peer {
		ips := make([]string, 0, len(peer.TailscaleIPs))
		for _, ip := range peer.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		name := peer.HostName
		if name == "" {
			name = strings.TrimSuffix(peer.DNSName, ".")
		}
		peers = append(peers, tailscalePeer{
			ID:       string(peer.ID),
			Name:     name,
			DNSName:  strings.TrimSuffix(peer.DNSName, "."),
			IPs:      ips,
			Online:   peer.Online,
			ExitNode: peer.ExitNodeOption,
		})
	}
	sort.Slice(peers, func(a, b int) bool { return peers[a].Name < peers[b].Name })
	encoded, err := json.Marshal(peers)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}
