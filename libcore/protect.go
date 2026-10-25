package libcore

// libneko/protect_server 的自实现替代：
// unix socket 服务端，接收主进程通过 SCM_RIGHTS 发来的 fd，
// 在 :bg 进程中回调 VpnService.protect。
// 协议与 sendFdToProtect（nb4a.go）对应：收 fd → 回调 → 回写 1 字节 ack。

import (
	"io"
	"log"
	"net"
	"os"

	"golang.org/x/sys/unix"
)

type protectServer struct {
	listener *net.UnixListener
	done     chan struct{}
}

// protectSocketPath 是主进程 protect server 的监听地址，由 InitCore 用
// internalAssets（filesDir）推导，两个进程算出同一个绝对路径。
var protectSocketPath string

// StartProtectServer 由主进程调用（只有它持有 VpnService）。:bg 进程里的
// box 通过 SCM_RIGHTS 把 socket fd 送到这里，再由主进程执行
// VpnService.protect——protect 必须在持有 VpnService 的进程里调用，
// 在 :bg 里调用 DataStore.vpnService 永远是 null，会让所有出站 socket
// 带上 VPN fwmark 回环进自己的 TUN（DNS connection refused 的根因）。
func StartProtectServer() {
	log.Println("[protect] StartProtectServer isBg=", isBgProcess, "path=", protectSocketPath)
	if isBgProcess || protectSocketPath == "" {
		log.Println("[protect] skipped")
		return
	}
	if protectCloser != nil {
		protectCloser.Close()
	}
	protectCloser = serveProtect(protectSocketPath, func(fd int) {
		intfBox.AutoDetectInterfaceControl(int32(fd))
	})
	if protectCloser == nil {
		log.Println("[protect] serveProtect FAILED for", protectSocketPath)
	} else {
		log.Println("[protect] listening on", protectSocketPath)
	}
}

func (p *protectServer) Close() error {
	err := p.listener.Close()
	<-p.done
	return err
}

// serveProtect 监听 unix socket path（相对路径，位于 no_backup 工作目录下）。
// listen 失败时返回 nil（调用方按不可 protect 降级处理）。
func serveProtect(path string, callback func(fd int)) io.Closer {
	_ = os.Remove(path)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		log.Println("serveProtect listen failed:", err)
		return nil
	}
	server := &protectServer{listener: listener, done: make(chan struct{})}
	go server.loop(callback)
	return server
}

func (p *protectServer) loop(callback func(fd int)) {
	defer close(p.done)
	for {
		conn, err := p.listener.AcceptUnix()
		if err != nil {
			return // listener 已关闭
		}
		// 并发处理：串行时 JNI protect 回调逐一排队，主进程
		// sendFdToProtect 的 100ms 超时容易失败 → fd 未 protect，
		// 测速流量回环进 tun（真机日志可见 tun-in 收到测试包）。
		go handleProtectConn(conn, callback)
	}
}

func handleProtectConn(conn *net.UnixConn, callback func(fd int)) {
	defer conn.Close()
	// 并发 goroutine 中兜底：单次 protect 失败（如 VPN 关闭中 JNI 回调异常）
	// 不应击垮整个 :bg 进程。
	defer func() {
		if r := recover(); r != nil {
			log.Println("protect: handler panic:", r)
		}
	}()

	buf := make([]byte, 1)
	oob := make([]byte, unix.CmsgSpace(4))
	_, oobn, _, _, err := conn.ReadMsgUnix(buf, oob)
	if err != nil {
		log.Println("protect: read msg failed:", err)
		return
	}
	messages, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(messages) == 0 {
		log.Println("protect: parse control message failed:", err)
		return
	}
	fds, err := unix.ParseUnixRights(&messages[0])
	if err != nil || len(fds) == 0 {
		log.Println("protect: parse unix rights failed:", err)
		return
	}

	callback(fds[0])

	// ack：客户端（sendFdToProtect）等待 1 字节响应
	_, _ = conn.Write([]byte{1})
}
