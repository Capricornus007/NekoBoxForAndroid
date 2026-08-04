package libcore

import (
	"net"
	"strconv"
	"sync"
	"time"

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

// defaultInterfaceState 仅用于诊断日志，描述当前缓存的默认接口。
func (m *interfaceMonitor) defaultInterfaceState() string {
	m.access.Lock()
	defer m.access.Unlock()
	if !m.defaultInterfaceInitialized {
		return "uninit"
	}
	if m.defaultInterface == nil {
		return "nil"
	}
	return m.defaultInterface.Name + "#" + strconv.Itoa(m.defaultInterface.Index)
}

// resolveInterface 解析默认物理接口。优先走 NetworkManager 平台缓存
// （UpdateInterfaces 刚从 Kotlin getInterfaces 刷入），避免
// control.NewDefaultInterfaceFinder().ByIndex 读 x/net/route 在 Android OEM
// （尤其 VPN 开启时）报 "route ip+net: no such network interface"。
// 真机日志：applied=0 / stuck 连发，但拨号未必失败——根因是找错了 finder。
func (m *interfaceMonitor) resolveInterface(interfaceName string, interfaceIndex int32) (*control.Interface, string, error) {
	idx := int(interfaceIndex)

	// 1) NetworkManager.InterfaceFinder（平台 UpdateInterfaces 已写入）
	if m.wrapper.networkManager != nil {
		if iif, err := m.wrapper.networkManager.InterfaceFinder().ByIndex(idx); err == nil && iif != nil {
			return iif, "nm-finder", nil
		}
		// 2) NetworkInterfaces 缓存按 index/name 匹配
		for _, ni := range m.wrapper.networkManager.NetworkInterfaces() {
			if ni.Index == idx || (interfaceName != "" && ni.Name == interfaceName) {
				iface := ni.Interface
				return &iface, "nm-cache", nil
			}
		}
	}

	// 3) 系统路由表 finder（Android 上常失败，保留作非平台回退）
	if iif, err := control.NewDefaultInterfaceFinder().ByIndex(idx); err == nil && iif != nil {
		return iif, "route-finder", nil
	}

	// 4) net.InterfaceByIndex
	if nif, err := net.InterfaceByIndex(idx); err == nil && nif != nil {
		return &control.Interface{
			Index:        nif.Index,
			MTU:          nif.MTU,
			Name:         nif.Name,
			HardwareAddr: nif.HardwareAddr,
			Flags:        nif.Flags,
		}, "net-by-index", nil
	}

	// 5) 最后兜底：用 Kotlin 上报的 name+index 构造（足够触发 notify→ResetNetwork）
	if interfaceName != "" && idx > 0 {
		return &control.Interface{
			Index: idx,
			Name:  interfaceName,
			Flags: net.FlagUp | net.FlagRunning,
		}, "kotlin-fallback", nil
	}

	return nil, "", E.New("interface not found: ", interfaceName, "#", idx)
}

// applyDefaultInterface 写入 defaultInterface 并在变化时通知回调
// （回调链 → NetworkManager.notifyInterfaceUpdate → ResetNetwork）。
func (m *interfaceMonitor) applyDefaultInterface(newInterface *control.Interface, source string) {
	m.access.Lock()
	oldInterface := m.defaultInterface
	m.defaultInterface = newInterface
	if m.defaultInterfaceInitialized && oldInterface != nil &&
		oldInterface.Name == newInterface.Name && oldInterface.Index == newInterface.Index {
		m.access.Unlock()
		m.logger.Info("UpdateDefaultInterface unchanged name=", newInterface.Name,
			" index=", newInterface.Index, " source=", source, " skip callbacks")
		return
	}
	m.defaultInterfaceInitialized = true
	callbacks := m.callbacks.Array()
	oldDesc := "nil"
	if oldInterface != nil {
		oldDesc = oldInterface.Name + "#" + strconv.Itoa(oldInterface.Index)
	}
	m.access.Unlock()
	m.logger.Info("UpdateDefaultInterface applied ", oldDesc, " -> ",
		newInterface.Name, "#", newInterface.Index, " source=", source,
		" callbacks=", len(callbacks))
	for _, callback := range callbacks {
		callback(newInterface, 0)
	}
}

// UpdateDefaultInterface 实现 InterfaceUpdateListener（Kotlin 回调入口）。
func (m *interfaceMonitor) UpdateDefaultInterface(interfaceName string, interfaceIndex int32) {
	m.logger.Info("UpdateDefaultInterface enter name=", interfaceName,
		" index=", interfaceIndex, " current=", m.defaultInterfaceState())

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
		m.logger.Info("UpdateDefaultInterface cleared (index=-1), notify callbacks")
		for _, callback := range callbacks {
			callback(nil, 0)
		}
		return
	}

	newInterface, source, err := m.resolveInterface(interfaceName, interfaceIndex)
	if err != nil {
		// 瞬时未就绪：短重试；仍失败则 kotlin-fallback 应已兜住，此处极少到达
		m.logger.Error(E.Cause(err, "find updated interface: ", interfaceName),
			" current=", m.defaultInterfaceState(), " will retry 5x200ms")
		go m.retryUpdateDefaultInterface(interfaceName, interfaceIndex, 5)
		return
	}
	m.applyDefaultInterface(newInterface, source)
}

// retryUpdateDefaultInterface 弥补 resolve 的瞬时失败。
func (m *interfaceMonitor) retryUpdateDefaultInterface(interfaceName string, interfaceIndex int32, attempts int) {
	for i := 0; i < attempts; i++ {
		time.Sleep(200 * time.Millisecond)
		// 刷新平台列表后再 resolve
		if m.wrapper.networkManager != nil {
			_ = m.wrapper.networkManager.UpdateInterfaces()
		}
		newInterface, source, err := m.resolveInterface(interfaceName, interfaceIndex)
		if err != nil {
			m.logger.Info("retryUpdateDefaultInterface attempt=", i+1, "/", attempts,
				" resolve failed: ", err)
			continue
		}
		m.applyDefaultInterface(newInterface, source+"-retry"+strconv.Itoa(i+1))
		return
	}
	m.logger.Error("default interface stuck after retry exhausted target=",
		interfaceName, "#", interfaceIndex, " current=", m.defaultInterfaceState(),
		" waiting for next callback")
}
