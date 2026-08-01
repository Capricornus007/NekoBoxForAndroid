package libcore

import (
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// InterfaceUpdateListener 由 Kotlin 侧实现回调：
// ConnectivityManager 默认网络变化时通知 Go 默认物理接口（名称 + index，-1 表示无网络）。
// 参考 husi libcore/tun.go。
type InterfaceUpdateListener interface {
	UpdateDefaultInterface(interfaceName string, interfaceIndex int32)
}

var (
	_ tun.DefaultInterfaceMonitor = (*interfaceMonitor)(nil)
	_ InterfaceUpdateListener     = (*interfaceMonitor)(nil)
)

// interfaceMonitor 是完整的默认接口监视器（替代旧 interfaceMonitorStub 空实现）。
// 官方内核 v1.13 只要注册了 PlatformInterface 就强制使用平台监视器
// （route/network.go: usePlatformDefaultInterfaceMonitor = platformInterface != nil），
// stub 的 DefaultInterface()=nil 会导致所有拨号报 "no available network interface"。
type interfaceMonitor struct {
	wrapper                     *boxPlatformInterfaceWrapper
	access                      sync.Mutex
	callbacks                   list.List[tun.DefaultInterfaceUpdateCallback]
	logger                      logger.Logger
	myInterfaces                []string
	defaultInterface            *control.Interface
	defaultInterfaceInitialized bool
}

// wrapper 指针延迟访问 networkManager：
// CreateDefaultInterfaceMonitor 先于 PlatformInterface.Initialize 被调用（box.go），
// 回调发生时 Initialize 已执行完毕。
func newInterfaceMonitor(w *boxPlatformInterfaceWrapper, l logger.Logger) *interfaceMonitor {
	return &interfaceMonitor{wrapper: w, logger: l}
}

func (m *interfaceMonitor) Start() error {
	return intfBox.StartDefaultInterfaceMonitor(m)
}

func (m *interfaceMonitor) Close() error {
	return intfBox.CloseDefaultInterfaceMonitor(m)
}

func (m *interfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.defaultInterface
}

// Kotlin 侧 DefaultNetworkListener 报告的本来就是物理接口（避开 VPN），无需 override。
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

// sing-tun v0.8.12 起接口为 MyInterfaces() []string（旧版 MyInterface() string）
func (m *interfaceMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterfaces
}

// UpdateDefaultInterface 实现 InterfaceUpdateListener（Kotlin 回调入口）。
func (m *interfaceMonitor) UpdateDefaultInterface(interfaceName string, interfaceIndex int32) {
	// 先刷新平台接口列表（NetworkManager 仅在平台分支缓存，拨号路径依赖之）
	if m.wrapper.networkManager != nil {
		if err := m.wrapper.networkManager.UpdateInterfaces(); err != nil {
			m.logger.Error(E.Cause(err, "update interfaces"))
		}
	}
	if interfaceIndex == -1 {
		m.access.Lock()
		m.defaultInterface = nil
		m.defaultInterfaceInitialized = true
		callbacks := m.callbacks.Array()
		m.access.Unlock()
		for _, callback := range callbacks {
			callback(nil, 0)
		}
		return
	}
	newInterface, err := control.NewDefaultInterfaceFinder().ByIndex(int(interfaceIndex))
	if err != nil {
		m.logger.Error(E.Cause(err, "find updated interface: ", interfaceName))
		return
	}
	m.access.Lock()
	oldInterface := m.defaultInterface
	m.defaultInterface = newInterface
	if m.defaultInterfaceInitialized && oldInterface != nil &&
		oldInterface.Name == newInterface.Name && oldInterface.Index == newInterface.Index {
		m.access.Unlock()
		return
	}
	m.defaultInterfaceInitialized = true
	callbacks := m.callbacks.Array()
	m.access.Unlock()
	for _, callback := range callbacks {
		callback(newInterface, 0)
	}
}
