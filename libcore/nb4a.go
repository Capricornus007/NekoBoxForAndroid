package libcore

import (
	"fmt"
	"libcore/device"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"time"
	_ "unsafe"

	"log"

	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/unix"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	platformLog.Truncate()
}

func ForceGc() {
	go debug.FreeOSMemory()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	defer device.DeferPanicToError("InitCore", func(err error) { log.Println(err) })
	isBgProcess = strings.HasSuffix(process, ":bg")

	// 記憶體策略（mod-49，2026-09-17 真機實測修訂）：
	// 空載存活堆僅 ~40MB、6×20MB 併發穿透下載峰值 ~47MB（clash /memory inuse）。
	// mod-47 教訓：上限設低於存活堆會觸發 gcpacer 永久滿轉（8 個 GC worker 全核
	// 滿載、手機發燙），上限必須遠高於存活堆。mod-48 的 1GB 修復了 CPU，但預設
	// GCPercent=100 讓堆可膨脹到存活兩倍、且 Go 的 scavenger 遲遲不把空頁歸還
	// 作業系統，下載後 RSS 仍高居不下。
	// 現行三道槓桿：
	//   1. GCPercent=40：堆只漲到存活 1.4 倍就回收，壓住 RSS 上緣；
	//   2. 512MB 軟上限：為觀測峰值的 10 倍餘裕，任何正規用量都碰不到，
	//      只在病態膨脹時兜底，gcpacer 絕不會追趕達不到的目標；
	//   3. bg 進程每 60s FreeOSMemory()：主動把已釋放頁歸還 OS（Go 預設
	//      打散歸還，空載時 RSS 會滯留在歷史高點）。小堆上 GC 僅毫秒級，
	//      CPU 開銷可忽略。
	debug.SetGCPercent(40)
	debug.SetMemoryLimit(512 << 20)
	if isBgProcess {
		go func() {
			defer device.DeferPanicToError("freeMemoryLoop", func(err error) { log.Println(err) })
			for {
				time.Sleep(60 * time.Second)
				debug.FreeOSMemory()
			}
		}()
	}

	intfNB4A = if1
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	os.MkdirAll(tmp, 0755)
	os.Chdir(tmp)

	// sing-box fs
	resourcePaths = append(resourcePaths, externalAssets)
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	setupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"), isBgProcess, !logEnable)

	// Set up some component
	go func() {
		defer device.DeferPanicToError("InitCore-go", func(err error) { log.Println(err) })
		device.GoDebug(process)

		// certs: use the Java-provided system trust anchors when registered,
		// otherwise fall back to an exported ca.pem bundle.
		if androidCAStore != nil {
			if pem := androidCAStore.Certificates(); len(pem) > 0 {
				updateRootCACerts(pem)
			}
		} else if pem, err := os.ReadFile(externalAssetsPath + "ca.pem"); err == nil {
			updateRootCACerts(pem)
		}

		// bg
		if isBgProcess {
			extractAssets()
		}
	}()
}

func sendFdToProtect(fd int, path string) error {
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return fmt.Errorf("failed to create unix socket: %w", err)
	}
	defer unix.Close(socketFd)

	var timeout unix.Timeval
	timeout.Usec = 100 * 1000

	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout)
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout)

	err = unix.Connect(socketFd, &unix.SockaddrUnix{Name: path})
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}

	err = unix.Sendmsg(socketFd, nil, unix.UnixRights(fd), nil, 0)
	if err != nil {
		return fmt.Errorf("failed to send: %w", err)
	}

	dummy := []byte{1}
	n, err := unix.Read(socketFd, dummy)
	if err != nil {
		return fmt.Errorf("failed to receive: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("socket closed unexpectedly")
	}
	return nil
}
