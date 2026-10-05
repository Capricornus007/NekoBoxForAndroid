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
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/tailscale/ipn"
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

// tailscaleReady reports backend and exit selection readiness, not packet reachability.
func tailscaleReady(status *adapter.TailscaleEndpointStatus, exitNodeWanted bool) bool {
	if status == nil || status.BackendState != ipn.Running.String() {
		return false
	}
	if !exitNodeWanted {
		return true
	}
	// ExitNodeStatus comes from a netmap snapshot that can miss peer deltas.
	// Only the live peer state establishes current selection and approval.
	for _, peer := range nativeTailscalePeers(status) {
		if peer != nil && peer.ExitNode && peer.ExitNodeOption {
			return true
		}
	}
	return false
}

// TailscaleWaitReady blocks until the node is running, and its exit node is selected when
// the profile configures one, or the timeout passes. Login state is reported so the caller
// can show why a node never came up.
func TailscaleWaitReady(i *BoxInstance, tag string, exitNodeWanted bool, timeoutMs int32) (err error) {
	defer deferPanicToError("box.TailscaleWaitReady", func(err_ error) { err = err_ })
	endpoint, lifetime, release, err := acquireTailscale(i, tag)
	if err != nil {
		return err
	}
	defer release()
	ctx, cancel := context.WithTimeout(lifetime, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	lastState := ""
	for {
		status, statusErr := firstTailscaleStatus(ctx, endpoint)
		if statusErr == nil && status != nil {
			lastState = status.BackendState
			if tailscaleReady(status, exitNodeWanted) {
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
	endpoint, lifetime, release, err := acquireTailscale(i, tag)
	if err != nil {
		return "", err
	}
	defer release()
	ctx, cancel := context.WithTimeout(lifetime, 5*time.Second)
	defer cancel()
	status, err := firstTailscaleStatus(ctx, endpoint)
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
	endpoint, lifetime, release, err := acquireTailscale(i, tag)
	if err != nil {
		return "", err
	}
	defer release()
	ctx, cancel := context.WithTimeout(lifetime, 5*time.Second)
	defer cancel()
	status, err := firstTailscaleStatus(ctx, endpoint)
	if err != nil {
		return "", err
	}
	nativePeers := nativeTailscalePeers(status)
	peers := make([]tailscalePeer, 0, len(nativePeers))
	for _, peer := range nativePeers {
		ips := append([]string{}, peer.TailscaleIPs...)
		name := peer.HostName
		if name == "" {
			name = strings.TrimSuffix(peer.DNSName, ".")
		}
		peers = append(peers, tailscalePeer{
			ID:       peer.StableID,
			Name:     name,
			DNSName:  strings.TrimSuffix(peer.DNSName, "."),
			IPs:      ips,
			Online:   peer.Online,
			ExitNode: peer.ExitNodeOption,
		})
	}
	sort.Slice(peers, func(a, b int) bool {
		if peers[a].Name != peers[b].Name {
			return peers[a].Name < peers[b].Name
		}
		return peers[a].ID < peers[b].ID
	})
	encoded, err := json.Marshal(peers)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}
