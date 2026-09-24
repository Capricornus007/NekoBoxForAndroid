package libcore

import (
	"testing"
	"time"
)

// awaitAssetsReady 掛在 BoxInstance.Start() 最前面，它自己絕對不能變成新的卡點。
// 這兩個案例守的就是「不該等的時候必須立刻返回」。
//
// 沒有測試「該等的時候真的有等」，是因為那要等滿 15 秒的超時才算完；那段是一個
// 直白的 select，風險遠低於把 Start() 變成長期阻塞。

func TestAwaitAssetsReadyReturnsWhenNothingScheduled(t *testing.T) {
	previous := assetsExtractionScheduled.Swap(false)
	defer assetsExtractionScheduled.Store(previous)

	done := make(chan struct{})
	go func() {
		awaitAssetsReady()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("blocked even though no asset extraction was scheduled")
	}
}

func TestAwaitAssetsReadyReturnsWhenAlreadyExtracted(t *testing.T) {
	previous := assetsExtractionScheduled.Swap(true)
	defer assetsExtractionScheduled.Store(previous)

	select {
	case <-assetsReady:
	default:
		close(assetsReady) // 只在還沒關閉時關，避免 close of closed channel
	}

	start := time.Now()
	awaitAssetsReady()
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("waited %v on an already-closed channel", elapsed)
	}
}
