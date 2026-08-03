package libcore

import (
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

// UpdateDefaultInterface 实现 InterfaceUpdateListener（Kotlin 回调入口）。
func (m *interfaceMonitor) UpdateDefaultInterface(interfaceName string, interfaceIndex int32) {
	// 诊断：夜间断连/飞行模式恢复时，核对 Kotlin 上报与 Go 侧 ByIndex 结果是否一致。
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
	newInterface, err := control.NewDefaultInterfaceFinder().ByIndex(int(interfaceIndex))
	if err != nil {
		// 启动早期接口/路由表可能尚未就绪（真机日志：
		// "find updated interface: wlan0: route ip+net: no such network interface"）。
		// 直接丢弃会让 defaultInterface 永久为 nil，该 box 的所有拨号秒报
		// "no available network interface"，故后台重试几次。
		// 诊断重点：重试窗口仅 5×200ms；若期间 defaultInterface 已是 stale 非 nil，
		// retry 会直接放弃，之后只能等下一次系统回调——夜间事件稀疏时即假死。
		m.logger.Error(E.Cause(err, "find updated interface: ", interfaceName),
			" current=", m.defaultInterfaceState(), " will retry 5x200ms")
		go m.retryUpdateDefaultInterface(interfaceName, interfaceIndex, 5)
		return
	}
	m.access.Lock()
	oldInterface := m.defaultInterface
	m.defaultInterface = newInterface
	if m.defaultInterfaceInitialized && oldInterface != nil &&
		oldInterface.Name == newInterface.Name && oldInterface.Index == newInterface.Index {
		m.access.Unlock()
		m.logger.Info("UpdateDefaultInterface unchanged name=", newInterface.Name,
			" index=", newInterface.Index, " skip callbacks")
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
		newInterface.Name, "#", newInterface.Index, " callbacks=", len(callbacks))
	for _, callback := range callbacks {
		callback(newInterface, 0)
	}
}

// retryUpdateDefaultInterface 弥补 UpdateDefaultInterface 的瞬时失败：
// 若期间已有其他回调成功设置了接口则直接放弃。
func (m *interfaceMonitor) retryUpdateDefaultInterface(interfaceName string, interfaceIndex int32, attempts int) {
	for i := 0; i < attempts; i++ {
		time.Sleep(200 * time.Millisecond)
		m.access.Lock()
		ready := m.defaultInterfaceInitialized && m.defaultInterface != nil
		cur := "uninit"
		if m.defaultInterfaceInitialized {
			if m.defaultInterface == nil {
				cur = "nil"
			} else {
				cur = m.defaultInterface.Name + "#" + strconv.Itoa(m.defaultInterface.Index)
			}
		}
		m.access.Unlock()
		if ready {
			// 关键：ready 只看「非 nil」，不区分 stale 与真正恢复。
			// 若 ByIndex 失败时残留旧接口，这里会直接放弃，导致 defaultInterface 卡在 stale。
			m.logger.Info("retryUpdateDefaultInterface abort attempt=", i+1, "/", attempts,
				" reason=already-set current=", cur, " target=", interfaceName, "#", interfaceIndex)
			return
		}
		newInterface, err := control.NewDefaultInterfaceFinder().ByIndex(int(interfaceIndex))
		if err != nil {
			m.logger.Info("retryUpdateDefaultInterface attempt=", i+1, "/", attempts,
				" ByIndex failed: ", err, " current=", cur)
			continue
		}
		m.access.Lock()
		if m.defaultInterfaceInitialized && m.defaultInterface != nil {
			cur2 := m.defaultInterface.Name + "#" + strconv.Itoa(m.defaultInterface.Index)
			m.access.Unlock()
			m.logger.Info("retryUpdateDefaultInterface abort after ByIndex ok attempt=", i+1,
				" reason=already-set current=", cur2)
			return
		}
		m.defaultInterface = newInterface
		m.defaultInterfaceInitialized = true
		callbacks := m.callbacks.Array()
		m.access.Unlock()
		for _, callback := range callbacks {
			callback(newInterface, 0)
		}
		m.logger.Info("default interface recovered after retry: ", interfaceName,
			"#", newInterface.Index, " attempt=", i+1)
		return
	}
	// 重试耗尽：此后无周期 reconcile，只能等下一次 Kotlin 回调。
	// 若日志出现本行且之后长时间无 UpdateDefaultInterface enter，即可坐实
	// 「一次性失败 + 休眠期回调稀疏 → 永久卡死」。
	m.logger.Error("default interface stuck after retry exhausted target=",
		interfaceName, "#", interfaceIndex, " current=", m.defaultInterfaceState(),
		" waiting for next callback")
}
