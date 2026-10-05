package libcore

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/sagernet/sing-box/protocol/tailscale"
)

// Deadlines are cooperative: callers retain ownership until local backend work returns.
const tailscaleChangeTimeout = 5 * time.Second

// TailscaleExitNodeChange owns the core's exact exit-only undo token.
// The caller must finalize after saving the canonical SavedValue, or roll back on save failure.
type TailscaleExitNodeChange struct {
	mu       sync.Mutex
	instance *BoxInstance
	change   *tailscale.ExitNodeChange
}

func SetTailscaleExitNode(i *BoxInstance, tag, stableID string) (*TailscaleExitNodeChange, error) {
	endpoint, ctx, release, err := acquireTailscale(i, tag)
	if err != nil {
		return nil, err
	}
	defer release()
	ctx, cancel := context.WithTimeout(ctx, tailscaleChangeTimeout)
	defer cancel()
	change, err := endpoint.BeginTailscaleExitNodeChange(ctx, stableID)
	if err != nil {
		return nil, err
	}
	return &TailscaleExitNodeChange{instance: i, change: change}, nil
}

func (c *TailscaleExitNodeChange) SavedValue() string {
	if c == nil || c.change == nil {
		return ""
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.change.SavedValue()
}

func (c *TailscaleExitNodeChange) finalize(rollback bool) error {
	if c == nil || c.instance == nil || c.change == nil {
		return errors.New("tailscale:conflict: invalid exit change")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.instance.access.Lock()
	ctx, release, err := c.instance.admitTailscaleLocked()
	c.instance.access.Unlock()
	if err != nil {
		return err
	}
	defer release()
	if !rollback {
		return c.change.Commit()
	}
	// Rollback is independent of the apply request's cancellation, but not of box shutdown.
	ctx, cancel := context.WithTimeout(ctx, tailscaleChangeTimeout)
	defer cancel()
	return c.change.Rollback(ctx)
}

func (c *TailscaleExitNodeChange) Commit() error {
	return c.finalize(false)
}

func (c *TailscaleExitNodeChange) Rollback() error {
	return c.finalize(true)
}
