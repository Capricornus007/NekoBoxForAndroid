package libcore

// neko_log（libneko）的自实现替代：
// 带大小截断的文件日志 writer，同时接管标准库 log 的输出。

import (
	"log"
	"os"
	"sync"
)

type fileLogWriter struct {
	access   sync.Mutex
	file     *os.File
	path     string
	maxSize  int64
	disabled bool
}

var platformLog = new(fileLogWriter)

// setupLog 初始化日志文件；truncateOnStart 时先清空旧日志。
func setupLog(maxSizeBytes int, path string, truncateOnStart bool, disabled bool) {
	platformLog.access.Lock()
	defer platformLog.access.Unlock()

	platformLog.maxSize = int64(maxSizeBytes)
	platformLog.path = path
	platformLog.disabled = disabled

	if truncateOnStart {
		_ = os.Remove(path)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		log.Println("open log file failed:", err)
		return
	}
	platformLog.file = file

	// 标准库 log 也写入同一文件
	log.SetOutput(platformLog)
	log.SetFlags(log.LstdFlags | log.LUTC)
}

func (w *fileLogWriter) Write(p []byte) (n int, err error) {
	w.access.Lock()
	defer w.access.Unlock()
	if w.disabled || w.file == nil {
		return len(p), nil
	}
	// 超过上限时截断文件（保留新内容，简单环形策略）
	if info, statErr := w.file.Stat(); statErr == nil && info.Size()+int64(len(p)) > w.maxSize {
		_ = w.file.Truncate(0)
		_, _ = w.file.Seek(0, 0)
	}
	return w.file.Write(p)
}

// Truncate 清空日志文件（对应原 neko_log.LogWriter.Truncate，供 NekoLogClear 调用）。
func (w *fileLogWriter) Truncate() {
	w.access.Lock()
	defer w.access.Unlock()
	if w.file == nil {
		return
	}
	_ = w.file.Truncate(0)
	_, _ = w.file.Seek(0, 0)
}
