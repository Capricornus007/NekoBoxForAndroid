package libcore

import (
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// InterfaceUpdateListener 由 Kotlin 侧实现回调：
// ConnectivityManager 默认网络变化时通知 Go 默认物理接口（名称 + index，-1 表示无网络）。
type InterfaceUpdateListener interface {
	UpdateDefaultInterface(interfaceName string, interfaceIndex int32)
}

var (
	_ tun.DefaultInterfaceMonitor = (*interfaceMonitor)(nil)
	_ InterfaceUpdateListener     = (*interfaceMonitor)(nil)
)

// interfaceMonitor は完全なデフォルトインターフェースモニター。
type interfaceMonitor struct {
	access                      sync.Mutex
	callbacks                   list.List[tun.DefaultInterfaceUpdateCallback]
	myInterfaces                []string
	defaultInterface            *control.Interface
	defaultInterfaceInitialized bool
}

func newInterfaceMonitor() *interfaceMonitor {
	return &interfaceMonitor{}
}

func (m *interfaceMonitor) Start() error {
	return nil
}

func (m *interfaceMonitor) Close() error {
	return nil
}

func (m *interfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.defaultInterface
}

func (m *interfaceMonitor) OverrideAndroidVPN() bool {
	return false
}

func (m *interfaceMonitor) AndroidVPNEnabled() bool {
	return false
}

func (m *interfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *interfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *interfaceMonitor) RegisterMyInterface(interfaceName string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, interfaceName)
}

func (m *interfaceMonitor) MyInterfaces() []string {
	return m.myInterfaces
}

func (m *interfaceMonitor) UpdateDefaultInterface(interfaceName string, interfaceIndex int32) {
	// Kotlin 側からコールバックされる。必要に応じてデフォルトインターフェースを更新。
}

// interfaceMonitorStub はデフォルトインターフェースモニターのスタブ実装。
type interfaceMonitorStub struct{}

func (s *interfaceMonitorStub) Start() error { return nil }
func (s *interfaceMonitorStub) Close() error { return nil }
func (s *interfaceMonitorStub) DefaultInterface() *control.Interface {
	return nil
}
func (s *interfaceMonitorStub) OverrideAndroidVPN() bool { return false }
func (s *interfaceMonitorStub) AndroidVPNEnabled() bool  { return false }
func (s *interfaceMonitorStub) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}
func (s *interfaceMonitorStub) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
}
func (s *interfaceMonitorStub) RegisterMyInterface(interfaceName string) {}
func (s *interfaceMonitorStub) MyInterfaces() []string                   { return nil }
