package libcore

import (
	"errors"
	"runtime"
	"strings"
	"testing"
	"time"
)

// runOnFreshStack 把實際工作丟到另一條 goroutine 上，所以 panic 已經不在呼叫端那些
// defer 的覆蓋範圍內。它必須自己把 panic 轉成 error 並**一定**要送進 channel，
// 否則呼叫端的 <-done 會永久阻塞（等於把「閃退」換成「卡死」，更糟）。
// goroutineHeader 回傳「goroutine N [running]:」那一行，用來斷言工作確實落在另一條
// goroutine 上——這才是這個修復的本體，光看錯誤有沒有傳出去是分不出來的。
func goroutineHeader() string {
	buf := make([]byte, 128)
	n := runtime.Stack(buf, false)
	return strings.SplitN(string(buf[:n]), "\n", 2)[0]
}

func TestRunOnFreshStackConvertsPanicToErrorWithoutDeadlock(t *testing.T) {
	result := make(chan error, 1)
	go func() {
		result <- runOnFreshStack("testStart", func() error {
			panic("stack growth blew up")
		})
	}()

	select {
	case err := <-result:
		if err == nil {
			t.Fatal("expected an error from a panicking fn, got nil")
		}
		if !strings.Contains(err.Error(), "stack growth blew up") {
			t.Fatalf("panic message lost, got: %v", err)
		}
		if !strings.Contains(err.Error(), "testStart") {
			t.Fatalf("panic name context lost, got: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("runOnFreshStack deadlocked after the inner fn panicked")
	}
}

func TestRunOnFreshStackPropagatesErrorAndResult(t *testing.T) {
	sentinel := errors.New("tun device busy")
	if err := runOnFreshStack("testStart", func() error { return sentinel }); !errors.Is(err, sentinel) {
		t.Fatalf("expected the fn's error to pass through, got %v", err)
	}
	if err := runOnFreshStack("testStart", func() error { return nil }); err != nil {
		t.Fatalf("expected nil, got %v", err)
	}

	ran := false
	if err := runOnFreshStack("testStart", func() error { ran = true; return nil }); err != nil {
		t.Fatal(err)
	}
	if !ran {
		t.Fatal("fn never ran")
	}

	outer := goroutineHeader()
	if err := runOnFreshStack("testStart", func() error {
		if goroutineHeader() == outer {
			t.Error("fn ran on the caller goroutine; the stack-size fix is not in effect")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

// Start / Close 現在都走新堆疊；兩者的回傳錯誤必須仍然原封不動傳到呼叫端，
// 否則外層那個 os.ErrClosed 正規化與狀態機會看到不一樣的結果。
func TestBoxStartAndCloseRouteThroughFreshStack(t *testing.T) {
	startErr := errors.New("gvisor stack init failed")
	callerHeader := goroutineHeader()
	instance := &BoxInstance{
		startBox: func() error {
			if goroutineHeader() == callerHeader {
				t.Error("Start did not move the work onto a fresh goroutine")
			}
			return startErr
		},
	}

	if err := instance.Start(); !errors.Is(err, startErr) {
		t.Fatalf("Start lost the underlying error: %v", err)
	}

	closeErr := errors.New("teardown panicked internally")
	instance = &BoxInstance{
		state: boxStateStarted,
		closeBox: func() error {
			panic(closeErr.Error())
		},
	}
	err := instance.Close()
	if err == nil || !strings.Contains(err.Error(), "teardown panicked internally") {
		t.Fatalf("Close must surface a panic from the teardown, got %v", err)
	}
}
