package libcore

import (
	"errors"
	"fmt"
	"os"
	"testing"
)

// A box whose Start failed has already rolled its own resources back, so the
// subsequent Close reports os.ErrClosed. Close must normalize that to nil while
// keeping the original start failure recorded, and stay idempotent.
func TestBoxCloseAfterStartFailureNormalizesAlreadyClosed(t *testing.T) {
	startErr := errors.New("missing outbound dependency")
	closeCalls := 0
	instance := &BoxInstance{
		startBox: func() error {
			return startErr
		},
		closeBox: func() error {
			closeCalls++
			return fmt.Errorf("rollback completed: %w", os.ErrClosed)
		},
	}

	if err := instance.Start(); !errors.Is(err, startErr) {
		t.Fatalf("Start() error = %v, want original error %v", err, startErr)
	}
	if instance.state != boxStateStartFailed {
		t.Fatalf("state after failed Start() = %s, want %s", instance.state, boxStateStartFailed)
	}
	if !errors.Is(instance.startErr, startErr) {
		t.Fatalf("recorded start error = %v, want %v", instance.startErr, startErr)
	}

	if err := instance.Close(); err != nil {
		t.Fatalf("Close() after rollback error = %v, want nil", err)
	}
	if instance.state != boxStateClosed {
		t.Fatalf("state after Close() = %s, want %s", instance.state, boxStateClosed)
	}
	if err := instance.Close(); err != nil {
		t.Fatalf("repeated Close() error = %v, want nil", err)
	}
	if closeCalls != 1 {
		t.Fatalf("underlying Close() calls = %d, want 1", closeCalls)
	}
}

func TestBoxStartRejectsSecondAttemptAndCloseSurfacesUnknownErrors(t *testing.T) {
	startCalls := 0
	closeErr := errors.New("listener teardown failed")
	instance := &BoxInstance{
		startBox: func() error {
			startCalls++
			return nil
		},
		closeBox: func() error {
			return closeErr
		},
	}

	if err := instance.Start(); err != nil {
		t.Fatalf("Start() error = %v, want nil", err)
	}
	if instance.state != boxStateStarted {
		t.Fatalf("state after Start() = %s, want %s", instance.state, boxStateStarted)
	}
	if err := instance.Start(); err == nil {
		t.Fatal("second Start() error = nil, want already started")
	}
	if startCalls != 1 {
		t.Fatalf("underlying Start() calls = %d, want 1", startCalls)
	}

	if err := instance.Close(); !errors.Is(err, closeErr) {
		t.Fatalf("Close() error = %v, want %v surfaced", err, closeErr)
	}
	if instance.state != boxStateClosed {
		t.Fatalf("state after Close() = %s, want %s", instance.state, boxStateClosed)
	}
}
