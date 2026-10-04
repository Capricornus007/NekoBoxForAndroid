package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/tailscale"
)

// TailscaleStream is a single-consumer pull stream. Close may run concurrently with Next.
// Producers never wait for the consumer; status replaces its pending value.
type TailscaleStream struct {
	ctx      context.Context
	lifetime context.Context
	cancel   context.CancelFunc
	done     chan struct{}
	wake     chan struct{}
	mu       sync.Mutex
	queue    []string
	capacity int
	latest   bool
	terminal error
	closed   bool
}

func newTailscaleStream(ctx context.Context, capacity int, latest bool) *TailscaleStream {
	workCtx, cancel := context.WithCancel(ctx)
	return &TailscaleStream{
		ctx: workCtx, lifetime: ctx, cancel: cancel, done: make(chan struct{}), wake: make(chan struct{}, 1),
		capacity: capacity, latest: latest,
	}
}

func (s *TailscaleStream) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *TailscaleStream) publish(value string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.ctx.Err() != nil || s.terminal != nil {
		return
	}
	if s.latest && len(s.queue) != 0 {
		s.queue[0] = value
	} else if len(s.queue) < s.capacity {
		s.queue = append(s.queue, value)
	}
	s.signal()
}

func tailscaleOperationError(err error) error {
	if err == nil || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || strings.HasPrefix(err.Error(), "tailscale:") {
		return err
	}
	// Raw EOF is reserved for intentional stream completion across gomobile.
	return fmt.Errorf("tailscale:operation-failed: %w", err)
}

// run owns producer cleanup and releases box admission before signaling completion.
func (s *TailscaleStream) run(release func(), produce func(context.Context) error) {
	go func() {
		err := produce(s.ctx)
		if err == nil {
			err = io.EOF
		} else {
			err = tailscaleOperationError(err)
		}
		s.mu.Lock()
		s.terminal = err
		s.mu.Unlock()
		// Detach the completed producer's context from the box without discarding samples.
		s.cancel()
		release()
		close(s.done)
		s.signal()
	}()
}

func (s *TailscaleStream) Next(timeoutMs int32) (string, error) {
	if timeoutMs < 1 || timeoutMs > 5000 {
		return "", errors.New("tailscale:invalid-timeout: Next requires 1..5000 ms")
	}
	if s == nil || s.ctx == nil {
		return "", errors.New("tailscale:closed: invalid stream")
	}
	timer := time.NewTimer(time.Duration(timeoutMs) * time.Millisecond)
	defer timer.Stop()
	for {
		s.mu.Lock()
		if s.closed || s.lifetime.Err() != nil {
			s.queue = nil
			s.mu.Unlock()
			return "", context.Canceled
		}
		if len(s.queue) != 0 {
			value := s.queue[0]
			s.queue[0] = ""
			s.queue = s.queue[1:]
			s.mu.Unlock()
			return value, nil
		}
		err := s.terminal
		s.mu.Unlock()
		if err != nil {
			return "", err
		}
		select {
		case <-s.ctx.Done():
		case <-s.wake:
		case <-timer.C:
			return "", nil
		}
	}
}

func (s *TailscaleStream) Close() error {
	if s == nil || s.cancel == nil {
		return nil
	}
	s.mu.Lock()
	s.closed = true
	s.queue = nil
	s.mu.Unlock()
	s.cancel()
	<-s.done
	return nil
}

// Caller holds access until the work is registered, so Close cannot race Add/Wait.
func (i *BoxInstance) admitTailscaleLocked() (context.Context, func(), error) {
	if i.state == 2 || (i.ctx != nil && i.ctx.Err() != nil) {
		return nil, nil, errors.New("tailscale:closed: instance is closing")
	}
	if !i.running || i.ctx == nil || i.Box == nil {
		return nil, nil, errors.New("tailscale:not-running: instance has not successfully started")
	}
	i.tailscaleWork.Add(1)
	return i.ctx, i.tailscaleWork.Done, nil
}

func acquireTailscale(i *BoxInstance, tag string) (*tailscale.Endpoint, context.Context, func(), error) {
	if i == nil {
		return nil, nil, nil, errors.New("tailscale:not-running: nil instance")
	}
	i.access.Lock()
	defer i.access.Unlock()
	ctx, release, err := i.admitTailscaleLocked()
	if err != nil {
		return nil, nil, nil, err
	}
	endpoint, loaded := i.Box.Endpoint().Get(tag)
	ts, ok := endpoint.(*tailscale.Endpoint)
	if !loaded || !ok {
		release()
		return nil, nil, nil, errors.New("tailscale:endpoint-not-found: " + tag)
	}
	return ts, ctx, release, nil
}

type tailscaleStatusSource interface {
	SubscribeTailscaleStatus(context.Context, func(*adapter.TailscaleEndpointStatus)) error
}

// The subscription supplies the full inventory, even when the public JSON is capped.
// It returns only after the core has joined its callback workers.
func firstTailscaleStatus(ctx context.Context, source tailscaleStatusSource) (*adapter.TailscaleEndpointStatus, error) {
	child, cancel := context.WithCancel(ctx)
	defer cancel()
	values := make(chan *adapter.TailscaleEndpointStatus, 1)
	err := source.SubscribeTailscaleStatus(child, func(status *adapter.TailscaleEndpointStatus) {
		if status != nil {
			select {
			case values <- status:
			default:
			}
			cancel()
		}
	})
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	select {
	case status := <-values:
		return status, nil
	default:
		if err == nil {
			err = errors.New("tailscale:status-unavailable")
		}
		return nil, tailscaleOperationError(err)
	}
}

func ObserveTailscaleStatus(i *BoxInstance, tag string) (*TailscaleStream, error) {
	endpoint, ctx, release, err := acquireTailscale(i, tag)
	if err != nil {
		return nil, err
	}
	s := newTailscaleStream(ctx, 1, true)
	s.run(release, func(ctx context.Context) error {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		var projectionErr error
		err := endpoint.SubscribeTailscaleStatus(ctx, func(status *adapter.TailscaleEndpointStatus) {
			if projectionErr != nil {
				return
			}
			value, err := marshalTailscaleStatus(status)
			if err != nil {
				projectionErr = err
				cancel()
				return
			}
			s.publish(value)
		})
		if projectionErr != nil {
			return projectionErr
		}
		if err == nil {
			return errors.New("tailscale:status-ended: subscription stopped")
		}
		return err
	})
	return s, nil
}

const tailscaleStatusTimeout = 5 * time.Second

func TailscaleStatus(i *BoxInstance, tag string) (string, error) {
	endpoint, lifetime, release, err := acquireTailscale(i, tag)
	if err != nil {
		return "", err
	}
	defer release()
	ctx, cancel := context.WithTimeout(lifetime, tailscaleStatusTimeout)
	defer cancel()
	status, err := firstTailscaleStatus(ctx, endpoint)
	if err != nil {
		return "", err
	}
	return marshalTailscaleStatus(status)
}
