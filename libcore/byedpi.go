package libcore

import (
	"fmt"

	"libcore/protocol/byedpi"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func init() {
	// byeDPI 的出口 socket 是 C 线程自己 socket()/connect() 出来的，不过
	// sing-box 的 dialer，因此拿不到 AutoDetectInterfaceControl 的保护；
	// 这里把同一份实现递给保护它，否则 VPN 模式下它连出去的包会回环进 tun。
	byedpi.SetProtector(protectSocketFD)
}

// validateByeDPIOptions 在 box.New 之前挡掉两类必然失败的配置：
//  1. byedpi 自己挂 detour —— 它就是最底层，没有下一跳；
//  2. selector / urltest 分组把 byedpi 当成员节点 —— 分组测速会去 dial 目的地
//     本身而非代理服务器，量出来的延迟与实际链路无关，且 byeDPI runner 是按
//     CLI 策略常驻的，塞进分组等于无限开关。
func validateByeDPIOptions(options option.Options) error {
	byedpiTags := make(map[string]struct{})
	for index, outbound := range options.Outbounds {
		if outbound.Type != byedpi.TypeByeDPI {
			continue
		}
		tag := outbound.Tag
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		byedpiTags[tag] = struct{}{}
		if outboundOptions, ok := outbound.Options.(*byedpi.OutboundOptions); ok && outboundOptions.Detour != "" {
			return fmt.Errorf("byedpi outbound %q cannot be used with detour", tag)
		}
	}
	if len(byedpiTags) == 0 {
		return nil
	}
	for index, outbound := range options.Outbounds {
		tag := outbound.Tag
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		switch outbound.Type {
		case C.TypeSelector:
			selectorOptions := outbound.Options.(*option.SelectorOutboundOptions)
			for _, outboundTag := range selectorOptions.Outbounds {
				if _, ok := byedpiTags[outboundTag]; ok {
					return fmt.Errorf("selector outbound %q cannot reference byedpi outbound %q", tag, outboundTag)
				}
			}
		case C.TypeURLTest:
			urlTestOptions := outbound.Options.(*option.URLTestOutboundOptions)
			for _, outboundTag := range urlTestOptions.Outbounds {
				if _, ok := byedpiTags[outboundTag]; ok {
					return fmt.Errorf("urltest outbound %q cannot reference byedpi outbound %q", tag, outboundTag)
				}
			}
		}
	}
	return nil
}
