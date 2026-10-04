package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

type tailscalePingSample struct {
	PeerID         string   `json:"peerId"`
	PeerIP         string   `json:"peerIp"`
	Sequence       int      `json:"sequence"`
	LatencyMs      *float64 `json:"latencyMs"`
	Path           string   `json:"path"`
	DERPRegionID   int32    `json:"derpRegionId"`
	DERPRegionCode string   `json:"derpRegionCode"`
	Error          string   `json:"error"`
}

func projectTailscalePing(peerID, peerIP string, sequence int, result *adapter.TailscalePingResult) tailscalePingSample {
	sample := tailscalePingSample{PeerID: peerID, PeerIP: peerIP, Sequence: sequence, Path: "unknown"}
	if result == nil {
		sample.Error = "missing ping result"
		return sample
	}
	sample.Error = result.Error
	if sample.Error != "" {
		return sample
	}
	if math.IsNaN(result.LatencyMs) || math.IsInf(result.LatencyMs, 0) || result.LatencyMs < 0 {
		sample.Error = "invalid ping latency"
		return sample
	}
	latency := result.LatencyMs
	sample.LatencyMs = &latency
	sample.DERPRegionID = result.DERPRegionID
	sample.DERPRegionCode = result.DERPRegionCode
	switch {
	case result.PeerRelay != "":
		sample.Path = "peer-relay"
	case result.IsDirect || result.Endpoint != "":
		sample.Path = "direct"
	case result.DERPRegionID != 0:
		sample.Path = "derp"
	}
	return sample
}

func resolveTailscalePingPeer(status *adapter.TailscaleEndpointStatus, stableID string) (string, error) {
	if stableID != "" && status != nil {
		for _, peer := range nativeTailscalePeers(status) {
			if peer.StableID == stableID {
				if ip := preferredTailscalePeerIP(peer); ip != "" {
					return ip, nil
				}
				break
			}
		}
	}
	return "", errors.New("tailscale:invalid-peer: stable ID has no current Tailscale address")
}

type tailscalePingSource interface {
	tailscaleStatusSource
	StartTailscalePing(context.Context, string, func(*adapter.TailscalePingResult)) error
}

func produceTailscalePing(ctx context.Context, source tailscalePingSource, stableID string, timeout time.Duration, publish func(string)) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	status, err := firstTailscaleStatus(ctx, source)
	if err != nil {
		return err
	}
	peerIP, err := resolveTailscalePingPeer(status, stableID)
	if err != nil {
		return err
	}
	pingCtx, stopPing := context.WithCancel(ctx)
	defer stopPing()
	sequence := 0
	var encodeErr error
	err = source.StartTailscalePing(pingCtx, peerIP, func(result *adapter.TailscalePingResult) {
		if sequence == 5 || pingCtx.Err() != nil {
			return
		}
		sequence++
		encoded, marshalErr := json.Marshal(projectTailscalePing(stableID, peerIP, sequence, result))
		if marshalErr != nil {
			encodeErr = marshalErr
			stopPing()
			return
		}
		publish(string(encoded))
		if sequence == 5 {
			stopPing()
		}
	})
	if encodeErr != nil {
		return encodeErr
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if sequence == 5 {
		return nil
	}
	if err == nil {
		return errors.New("tailscale:ping-ended: producer stopped before five samples")
	}
	return err
}

func StartTailscalePeerPing(i *BoxInstance, tag, stableID string, timeoutMs int32) (*TailscaleStream, error) {
	if timeoutMs < 1 || timeoutMs > 10000 {
		return nil, errors.New("tailscale:invalid-timeout: ping requires 1..10000 ms")
	}
	if stableID == "" {
		return nil, errors.New("tailscale:invalid-peer: empty stable ID")
	}
	endpoint, ctx, release, err := acquireTailscale(i, tag)
	if err != nil {
		return nil, err
	}
	s := newTailscaleStream(ctx, 5, false)
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	s.run(release, func(ctx context.Context) error {
		ctx, cancel := context.WithDeadline(ctx, deadline)
		defer cancel()
		return produceTailscalePing(ctx, endpoint, stableID, time.Duration(timeoutMs)*time.Millisecond, s.publish)
	})
	return s, nil
}
