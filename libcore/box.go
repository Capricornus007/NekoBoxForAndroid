package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	// 官方内核没有 conntrack（starifly fork 私有实现）。
	// 按迁移方针先跳过并留 debug 日志，待有具体案例再修。
	log.Println("DEBUG: ResetAllConnections(system=", system, ") skipped: official sing-box has no conntrack")
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api        *v2rayapi.StatsService
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, true)
}

// NewTestSingBoxInstance 供 URL 测速等一次性实例使用：不注册 PlatformLogWriter。
// 官方内核在 PlatformLogWriter != nil 时无条件创建 CacheFile 与 ClashServer
// （官方 box.go 的 needCacheFile/needClashAPI 分支）：主进程批量测速并发创建的
// 大量实例曾共享默认 cache.db（bbolt）把 freelist 写坏，并在 bbolt 定时器
// goroutine 里 panic 导致主进程闪退；即便退而求其次做文件隔离也是纯浪费——
// 测速实例根本不需要 cache 与 Clash API。置 nil 后两者均不再创建，
// box 日志回落到 stderr（logcat 仍可见）。
func NewTestSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, false)
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, platformLog bool) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// 官方内核不支持 fork 私有的 "geoip:xxx"/"geosite:xxx" 伪路径 local rule-set，
	// 这里预处理：从 geoip.db/geosite.db 生成 .srs 缓存并改写为真实路径。
	if options.Route != nil {
		err = prepareLocalGeoRuleSets(options.Route.RuleSet)
		if err != nil {
			cancel()
			return nil, fmt.Errorf("prepare geo rule-sets: %v", err)
		}
	}

	// create box
	// 测速实例（platformLog=false）传 nil：见 NewTestSingBoxInstance 批注。
	var logWriter sblog.PlatformWriter
	if platformLog {
		logWriter = boxPlatformLogWriter
		// 官方内核的 PlatformWriter 通道不做级别过滤（observable.go 无条件
		// 转发所有级别），在此记录配置级别供 WriteMessage 侧过滤；
		// 空级别对齐官方默认 trace。级别非法时 box.New 会报同样的错，此处忽略。
		if options.Log != nil && options.Log.Level != "" {
			if parsedLevel, parseErr := sblog.ParseLevel(options.Log.Level); parseErr == nil {
				setPlatformLogLevel(parsedLevel)
			}
		} else {
			setPlatformLogLevel(sblog.LevelTrace)
		}
	}
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: logWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	// 官方 experimental/v2rayapi 的 StatsService 即 adapter.ConnectionTracker
	b.v2api = v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api)
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	resp, err := b.v2api.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name:   fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct),
		Reset_: true,
	})
	if err != nil || resp.Stat == nil {
		return 0
	}
	return resp.Stat.Value
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		if b.selector.SelectOutbound(tag) {
			// 替代 fork 的 nekoutils.Selector_OnProxySelected 钩子。
			// 注意：仅覆盖 app 内的切换路径；通过 Clash API（yacd 面板）
			// 切换不会触发该回调（官方内核无此钩子，待有具体案例再修）。
			if intfNB4A != nil {
				intfNB4A.Selector_OnProxySelected(b.selector.Tag(), tag)
			}
			return true
		}
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		i = mainInstance
	}
	var client *http.Client
	if i == nil {
		// 无实例：直连测试
		client = &http.Client{Timeout: time.Duration(timeout) * time.Millisecond}
	} else {
		var connectionTracker adapter.ConnectionTracker
		if i.v2api != nil {
			connectionTracker = i.v2api
		}
		client = newProxyHTTPClient(i.Box, connectionTracker, timeout)
	}
	return urlTest(client, link)
}

// newProxyHTTPClient 替代 fork 的 boxapi.CreateProxyHttpClient：
// 经 box 的默认（final）outbound 拨号的 HTTP client。
func newProxyHTTPClient(b *box.Box, tracker adapter.ConnectionTracker, timeout int32) *http.Client {
	transport := &http.Transport{DisableKeepAlives: true}
	transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		outbound := b.Outbound().Default()
		if outbound == nil {
			return nil, E.New("no default outbound")
		}
		destination := M.ParseSocksaddr(addr)
		conn, err := outbound.DialContext(ctx, N.NetworkTCP, destination)
		if err != nil {
			return nil, err
		}
		if tracker != nil {
			conn = tracker.RoutedConnection(ctx, conn, adapter.InboundContext{
				Outbound:    outbound.Tag(),
				Destination: destination,
			}, nil, outbound)
		}
		return conn, nil
	}
	return &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeout) * time.Millisecond,
	}
}

// urlTest 替代 libneko/speedtest.UrlTest（UrlTestStandard_RTT 模式）：
// GET 请求，计时到收到响应头。
func urlTest(client *http.Client, link string) (int32, error) {
	req, err := http.NewRequest(http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	_ = resp.Body.Close()
	if resp.StatusCode >= 400 {
		return 0, E.New("unexpected status: ", resp.Status)
	}
	return int32(time.Since(start).Milliseconds()), nil
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = serveProtect("protect_path", func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
