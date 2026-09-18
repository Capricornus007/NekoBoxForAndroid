package byedpi

import "sync"

// protector 把“不让自连的 socket 掉回本机 tun”这件事交给 libcore 的既有实现。
// byeDPI 的出口 socket 是在 C 线程里直接 socket()/connect() 建立的，
// 绕过了 sing-box 的 dialer，所以必须自己回调 VpnService.protect。
// 这里用函数注入而非直接 import libcore：libcore 已经 import 本包，
// 反向依赖会形成 cycle。由 libcore 在 init 时 SetProtector 接上。
var (
	protectorAccess sync.RWMutex
	protectorFunc   func(fd int) error
)

// SetProtector 由 libcore 在 init 时接上。未接上时 byedpi_android_protect_fd
// 返回错误、连接直接被丢弃——宁可连接失败也不要冒出没 protect 的流量回环进 tun。
func SetProtector(fn func(fd int) error) {
	protectorAccess.Lock()
	defer protectorAccess.Unlock()
	protectorFunc = fn
}

func protector() func(fd int) error {
	protectorAccess.RLock()
	defer protectorAccess.RUnlock()
	return protectorFunc
}
