package libcore

import (
	"context"
	"errors"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
)

func awaitTailscaleDone(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("producer cleanup did not complete")
	}
}

func TestTailscaleStreamLatestAndQuietTimeout(t *testing.T) {
	s := newTailscaleStream(context.Background(), 1, true)
	ready := make(chan struct{})
	s.run(func() {}, func(ctx context.Context) error {
		for n := 0; n < 10000; n++ {
			s.publish("old")
		}
		s.publish("latest")
		close(ready)
		<-ctx.Done()
		return ctx.Err()
	})
	defer s.Close()
	awaitTailscaleDone(t, ready)
	if value, err := s.Next(100); err != nil || value != "latest" {
		t.Fatalf("latest slot: %q, %v", value, err)
	}
	if value, err := s.Next(1); err != nil || value != "" {
		t.Fatalf("quiet read: %q, %v", value, err)
	}
	for _, timeout := range []int32{0, -1, 5001} {
		if _, err := s.Next(timeout); err == nil {
			t.Fatal("invalid read timeout accepted")
		}
	}
}

func TestTailscaleStreamDrainsBeforeTerminal(t *testing.T) {
	for _, terminal := range []error{nil, context.DeadlineExceeded, errors.New("producer failed")} {
		s := newTailscaleStream(context.Background(), 5, false)
		s.run(func() {}, func(context.Context) error {
			for n := 0; n < 10; n++ {
				s.publish("sample")
			}
			return terminal
		})
		awaitTailscaleDone(t, s.done)
		if s.ctx.Err() == nil {
			t.Fatal("completed producer context remains attached to owner")
		}
		for n := 0; n < 5; n++ {
			if value, err := s.Next(100); err != nil || value != "sample" {
				t.Fatalf("sample %d missing: %v", n, err)
			}
		}
		want := terminal
		if want == nil {
			want = io.EOF
		}
		if _, err := s.Next(100); !errors.Is(err, want) {
			t.Fatalf("terminal = %v, want %v", err, want)
		}
		s.Close()
		s.Close()
	}
}

func TestTailscaleStreamTerminalMessages(t *testing.T) {
	for _, test := range []struct {
		terminal error
		message  string
	}{
		{nil, "EOF"},
		{io.EOF, "tailscale:operation-failed: EOF"},
		{errors.New("transport disconnected"), "tailscale:operation-failed: transport disconnected"},
		{context.DeadlineExceeded, "context deadline exceeded"},
		{context.Canceled, "context canceled"},
	} {
		s := newTailscaleStream(context.Background(), 5, false)
		s.run(func() {}, func(context.Context) error { return test.terminal })
		awaitTailscaleDone(t, s.done)
		_, err := s.Next(1)
		if err == nil || err.Error() != test.message {
			t.Fatalf("terminal = %v, want %q", err, test.message)
		}
		if test.terminal == nil && err != io.EOF {
			t.Fatal("natural completion is not raw EOF")
		}
		if test.terminal == io.EOF && !strings.HasPrefix(err.Error(), "tailscale:") {
			t.Fatal("operational EOF is indistinguishable from completion")
		}
		s.Close()
	}
}

func TestTailscaleStreamCloseUnblocksAndJoins(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s := newTailscaleStream(ctx, 1, true)
	cleanup := make(chan struct{})
	s.run(func() { close(cleanup) }, func(ctx context.Context) error {
		<-ctx.Done()
		return ctx.Err()
	})
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		if _, err := s.Next(5000); !errors.Is(err, context.Canceled) {
			t.Errorf("blocked read = %v", err)
		}
	}()
	var closers sync.WaitGroup
	for n := 0; n < 8; n++ {
		closers.Add(1)
		go func() { defer closers.Done(); s.Close() }()
	}
	closers.Wait()
	awaitTailscaleDone(t, readDone)
	awaitTailscaleDone(t, cleanup)
	s.publish("late")
	if _, err := s.Next(1); !errors.Is(err, context.Canceled) {
		t.Fatal("late value after close")
	}
}

func TestTailscaleStreamPublishReadCloseRace(t *testing.T) {
	for n := 0; n < 50; n++ {
		s := newTailscaleStream(context.Background(), 1, true)
		started := make(chan struct{})
		s.run(func() {}, func(ctx context.Context) error {
			close(started)
			for {
				select {
				case <-ctx.Done():
					return ctx.Err()
				default:
					s.publish("snapshot")
				}
			}
		})
		awaitTailscaleDone(t, started)
		readDone := make(chan struct{})
		go func() {
			defer close(readDone)
			for {
				_, err := s.Next(10)
				if errors.Is(err, context.Canceled) {
					return
				}
				if err != nil {
					t.Error(err)
					return
				}
			}
		}()
		s.Close()
		awaitTailscaleDone(t, readDone)
	}
}

func TestTailscaleStreamZeroValue(t *testing.T) {
	for _, stream := range []*TailscaleStream{nil, {}} {
		if _, err := stream.Next(1); err == nil {
			t.Fatal("invalid stream read accepted")
		}
		if err := stream.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestTailscaleStreamOwnerCancellationDiscardsCompletedQueue(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	s := newTailscaleStream(ctx, 5, false)
	s.run(func() {}, func(context.Context) error {
		s.publish("queued")
		return nil
	})
	awaitTailscaleDone(t, s.done)
	cancel()
	if _, err := s.Next(1); !errors.Is(err, context.Canceled) {
		t.Fatal("owner cancellation must discard completed samples")
	}
	s.Close()
}

func TestTailscaleStreamOwnerCancellationDiscardsQueue(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	s := newTailscaleStream(ctx, 5, false)
	s.run(func() {}, func(ctx context.Context) error {
		s.publish("queued")
		<-ctx.Done()
		return ctx.Err()
	})
	cancel()
	awaitTailscaleDone(t, s.done)
	if _, err := s.Next(1); !errors.Is(err, context.Canceled) {
		t.Fatal("owner cancellation must discard samples")
	}
	s.Close()
}

func TestTailscaleAdmissionRejectsDeadInstances(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	for _, instance := range []*BoxInstance{nil, {}, {state: 1, ctx: ctx}, {state: 2, ctx: ctx}} {
		if _, err := ObserveTailscaleStatus(instance, "ts"); err == nil {
			t.Fatal("status admitted without successful start")
		}
		if _, err := StartTailscalePeerPing(instance, "ts", "peer", 1000); err == nil {
			t.Fatal("ping admitted without successful start")
		}
		if _, err := SetTailscaleExitNode(instance, "ts", "peer"); err == nil {
			t.Fatal("mutation admitted without successful start")
		}
		if _, err := TailscalePeers(instance, "ts"); err == nil {
			t.Fatal("legacy peers admitted without successful start")
		}
		if _, err := TailscaleAuthURL(instance, "ts"); err == nil {
			t.Fatal("legacy auth admitted without successful start")
		}
		if err := TailscaleWaitReady(instance, "ts", false, 1); err == nil {
			t.Fatal("legacy readiness admitted without successful start")
		}
	}
}

func TestBoxCloseDrainsTailscaleOutsideAccess(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	instance := &BoxInstance{Box: &box.Box{}, ctx: ctx, cancel: cancel, state: 1, running: true}
	instance.access.Lock()
	lifetime, release, err := instance.admitTailscaleLocked()
	instance.access.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	// No native server is needed to exercise admission and shutdown ordering.
	instance.Box = nil
	workerDone := make(chan struct{})
	go func() {
		<-lifetime.Done()
		instance.access.Lock()
		if instance.state != 2 || instance.running {
			t.Error("cancellation preceded closing admission")
		}
		instance.access.Unlock()
		release()
		close(workerDone)
	}()
	closed := make(chan struct{})
	go func() { instance.Close(); close(closed) }()
	awaitTailscaleDone(t, closed)
	awaitTailscaleDone(t, workerDone)
	instance.Close()
}

type tailscaleStatusFixture struct {
	status  *adapter.TailscaleEndpointStatus
	cleaned bool
}

func (f *tailscaleStatusFixture) SubscribeTailscaleStatus(ctx context.Context, callback func(*adapter.TailscaleEndpointStatus)) error {
	callback(f.status)
	<-ctx.Done()
	f.cleaned = true
	return ctx.Err()
}

func TestFirstTailscaleStatusJoinsProducer(t *testing.T) {
	fixture := &tailscaleStatusFixture{status: statusWithPeers()}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if result, err := firstTailscaleStatus(ctx, fixture); err != nil || result != fixture.status || !fixture.cleaned {
		t.Fatal("snapshot did not join subscription cleanup")
	}
}

func TestTailscaleInvalidChange(t *testing.T) {
	for _, change := range []*TailscaleExitNodeChange{nil, {}} {
		if change.SavedValue() != "" || change.Commit() == nil || change.Rollback() == nil {
			t.Fatal("invalid change accepted")
		}
	}
}
