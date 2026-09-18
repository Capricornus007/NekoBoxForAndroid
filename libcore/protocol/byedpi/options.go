package byedpi

import "github.com/sagernet/sing-box/option"

// OutboundOptions 定义 byedpi outbound 的配置。byeDPI 是“深度包检测规避层”，
// 本身没有服务器位置：流量经它的 socketpair 桥进 C 层、由 C 层自己连出目标，
// 所以这里不接受 detour（byeDPI 必须是链路的最后一跳、直接落在物理网络上的那一层）。
type OutboundOptions struct {
	option.DialerOptions
	CLI string `json:"cli,omitempty"`
}
