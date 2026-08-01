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
		handleProtectConn(conn, callback)
	}
}

func handleProtectConn(conn *net.UnixConn, callback func(fd int)) {
	defer conn.Close()

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
