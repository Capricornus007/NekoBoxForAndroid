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
	"net/url"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
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

// 1.14.x: dialer.DoNotSelectInterface は廃止。interface 自動選択の制御は
// PlatformInterface/NetworkManager の AutoDetectInterface に委ねる（NekoBox は
// boxPlatformInterfaceWrapper.UsePlatformNetworkInterfaces() = false で無効化済み）。

var mainInstance *BoxInstance

type boxLifecycleState uint8

const (
	boxStateNew boxLifecycleState = iota
	boxStateStarting
	boxStateStarted
	boxStateStartFailed
	boxStateClosing
	boxStateClosed
)

func (s boxLifecycleState) String() string {
	switch s {
	case boxStateNew:
		return "new"
	case boxStateStarting:
		return "starting"
	case boxStateStarted:
		return "started"
	case boxStateStartFailed:
		return "start-failed"
	case boxStateClosing:
		return "closing"
	case boxStateClosed:
		return "closed"
	default:
		return fmt.Sprintf("unknown(%d)", uint8(s))
	}
}

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
	// 官方无 conntrack；等价能力是 NetworkManager.ResetNetwork()：
	// CloseAll 连接 + 通知 endpoint/inbound/outbound.InterfaceUpdated()
	// （hy2/quic 等会丢弃死路径上的会话，下次拨号重建）。
	// 正常切网由 interfaceMonitor → notifyInterfaceUpdate 自动 ResetNetwork
	// （对齐官方 libbox，app 侧不应再叠一层）。本函数仅供手动
	// Action.RESET_UPSTREAM_CONNECTIONS / wakeResetConnections 等显式入口。
	b := mainInstance
	if b == nil || b.Box == nil {
		log.Println("ResetAllConnections: no main instance, skip system=", system)
		return
	}
	b.Network().ResetNetwork(context.Background())
	log.Println("ResetAllConnections: Network.ResetNetwork() done system=", system)
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  boxLifecycleState

	// startBox/closeBox indirect the underlying box lifecycle so the wrapper's
	// state machine can be unit-tested without a real sing-box instance.
	startBox func() error
	closeBox func() error
	startErr error

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	urlTest      *group.URLTest
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
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, newBoxPlatformInterfaceWrapper())

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}
	if err = validateByeDPIOptions(options); err != nil {
		cancel()
		return nil, fmt.Errorf("validate config: %v", err)
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
		startBox:     instance.Start,
		closeBox:     instance.Close,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector / urlTest
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		} else if urlTest, ok := proxy.(*group.URLTest); ok {
			b.urlTest = urlTest
		}
	}

	return b, nil
}

// runOnFreshStack 把 fn 丟到一條全新的 goroutine 上執行，並等它結束（語意仍是同步）。
//
// 由 Java 執行緒經 gomobile 進來的呼叫，沿用宿主 pthread 的堆疊上限（Android 常見 8MB
// 出頭），而 gvisor TUN 樹的建立與拆除遞迴很深，超過就會 `fatal error: stack growth
// failed` 把整個 :bg 打死（golang/go#68760）。上游 sing-box 自己有
// libbox SetupOptions.FixAndroidStack 做同一件事，但我方不走 libbox CommandServer
// （用 gomobile-matsuri + box_include.go），那個開關對我們是死的，只能自己包。
// 新起的 goroutine 由 Go runtime 管理堆疊，可以正常增長。
//
// panic 必須在這裡就轉成 error 傳出去：fn 已經不在呼叫端那些 defer 的覆蓋範圍內了。
// assetsReady 在 :bg 行程把 APK 內的 geoip / geosite / yacd 資產解壓結束後關閉。
// InitCore 把解壓丟在一條分離的 goroutine 裡，而 BoxInstance.Start() 有可能先跑完，
// 這時核心讀不到 geo 檔並不會報錯，只是讓 geo 規則靜默失效——使用者看到的是
// 「規則沒作用」，而且重啟就好，極難歸因。
var (
	assetsReady               = make(chan struct{})
	assetsExtractionScheduled atomic.Bool
)

// assetsWaitTimeout 是上機安全閥：資產真的卡住時最多延後這麼久，而不是永遠連不上。
const assetsWaitTimeout = 15 * time.Second

// awaitAssetsReady 只在「確實排定了資產解壓」時等待。已解完時 channel 早已關閉，
// 這個 select 零開銷；非 :bg 行程根本不會排程解壓，直接返回，就不會被拖住。
func awaitAssetsReady() {
	if !assetsExtractionScheduled.Load() {
		return
	}
	select {
	case <-assetsReady:
	case <-time.After(assetsWaitTimeout):
		log.Println("box: assets still not ready after", assetsWaitTimeout, "- starting anyway")
	}
}

func runOnFreshStack(name string, fn func() error) error {
	done := make(chan error, 1)
	go func() {
		var err error
		// 先註冊傳送、後註冊 panic 轉換，這樣 LIFO 展開時轉換先跑、傳送後跑，
		// panic 才會帶著錯誤一起送出去而不是讓呼叫端永久阻塞。
		defer func() { done <- err }()
		defer device.DeferPanicToError(name, func(err_ error) { err = err_ })
		err = fn()
	}()
	return <-done
}

func (b *BoxInstance) Start() (err error) {
	// 先等資產解壓，別拿著 b.access 等：Close() 用的是同一把鎖。
	awaitAssetsReady()

	b.access.Lock()
	defer b.access.Unlock()

	if b.state != boxStateNew {
		return errors.New("already started")
	}

	b.state = boxStateStarting
	defer func() {
		if err != nil {
			b.state = boxStateStartFailed
			b.startErr = err
		} else {
			b.state = boxStateStarted
		}
	}()
	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	err = runOnFreshStack("box.Start", func() error {
		if b.startBox != nil {
			return b.startBox()
		}
		if b.Box != nil {
			return b.Box.Start()
		}
		return errors.New("box is nil")
	})
	return err
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	// no double close
	if b.state == boxStateClosed {
		return nil
	}
	previousState := b.state
	b.state = boxStateClosing
	defer func() {
		b.state = boxStateClosed
		// A partially started box already rolled its own resources back, so the
		// underlying Close reports os.ErrClosed. That is expected here and must not
		// mask the original start failure recorded in b.startErr.
		if errors.Is(err, os.ErrClosed) {
			log.Printf("box.Close: normalized already-closed previous=%s startError=%v", previousState, b.startErr)
			err = nil
		}
	}()
	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box —— 只有真正拆 gvisor 樹的那幾行要換到新堆疊上，上面的 mainInstance /
	// goServeProtect / cancel 狀態機留在原 goroutine，避免把同步語意搞亂。
	if b.cancel != nil {
		b.cancel()
	}
	err = runOnFreshStack("box.Close", func() error {
		if b.closeBox != nil {
			return b.closeBox()
		}
		if b.Box != nil {
			return b.Box.Close()
		}
		return nil
	})

	return err
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
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
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

func (b *BoxInstance) GetBalancerNow() string {
	if b.urlTest != nil {
		if selected := b.urlTest.Selected(N.NetworkTCP); selected != nil {
			return selected.Tag()
		}
	}
	return ""
}

func (b *BoxInstance) GetBalancerAll() []string {
	if b.urlTest != nil {
		return b.urlTest.All()
	}
	return nil
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		i = mainInstance
	}
	boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest link=%s timeout=%dms instance=%v", link, timeout, i != nil))
	if i == nil {
		// 无实例：直连测试（单 GET，计时含拨号）
		client := &http.Client{Timeout: time.Duration(timeout) * time.Millisecond}
		latency, err = urlTestDirect(client, link)
	} else {
		var connectionTracker adapter.ConnectionTracker
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		latency, err = urlTest(i.Box, connectionTracker, link, timeout)
	}
	boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest result latency=%dms err=%v", latency, err))
	return
}

// urlTestUserAgent 是探測請求帶的瀏覽器 UA：不少測速檔的 CDN 會直接拒掉 Go 的預設 UA。
const urlTestUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

// urlTest 替代 libneko/speedtest.UrlTest 与 fork 的 boxapi.CreateProxyHttpClient，
// 对齐 husi libcore/ping.go 的"显式拨号 + 连接复用 + 双 HEAD 请求"模式：
// 第一次 HEAD 预热（含握手，不计时），第二次 HEAD 复用同一连接计时。
// HEAD 被目标拒掉时两阶段一起退化成带浏览器 UA 的 GET，避免好节点被误报为失败。
// 收益：① 延迟为纯 RTT，跨协议可比、贴近实际使用时连接复用的体感；
// ② 第二次请求验证连接持续性——"首包能通但随即断开"的节点不再假成功。
// 超时由 ctx 全程控制（拨号 + 两次请求共用 timeout 预算）。
func urlTest(b *box.Box, tracker adapter.ConnectionTracker, link string, timeout int32) (int32, error) {
	linkURL, err := url.Parse(link)
	if err != nil {
		return 0, E.Cause(err, "parse test link")
	}
	hostname := linkURL.Hostname()
	port := linkURL.Port()
	if port == "" {
		switch linkURL.Scheme {
		case "http":
			port = "80"
		case "https":
			port = "443"
		default:
			return 0, E.New("unsupported test link scheme: ", linkURL.Scheme)
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	outbound := b.Outbound().Default()
	if outbound == nil {
		return 0, E.New("no default outbound")
	}
	destination := M.ParseSocksaddrHostPortStr(hostname, port)
	conn, err := outbound.DialContext(ctx, N.NetworkTCP, destination)
	if err != nil {
		return 0, err
	}
	if tracker != nil {
		conn = tracker.RoutedConnection(ctx, conn, adapter.InboundContext{
			Outbound:    outbound.Tag(),
			Destination: destination,
		}, nil, outbound)
	}
	defer conn.Close()

	// client 恒复用上面建立的连接（keep-alive）；重定向不跟随（generate_204 类直返）。
	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(context.Context, string, string) (net.Conn, error) {
				return conn, nil
			},
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	defer client.CloseIdleConnections()

	// 很多測速檔的 CDN 對 HEAD 回 405/403，或直接拒掉 Go 的預設 UA —— 節點明明是好的卻報失敗。
	// 所以 HEAD 碰壁且還沒超時時改用帶瀏覽器 UA 的 GET 重試，並讓後續量測沿用同一方法。
	probeMethod := http.MethodHead
	doProbe := func() error {
		req, err := http.NewRequestWithContext(ctx, probeMethod, link, nil)
		if err != nil {
			return err
		}
		if probeMethod == http.MethodGet {
			req.Header.Set("User-Agent", urlTestUserAgent)
		}
		resp, err := client.Do(req)
		if err != nil {
			return err
		}
		// GET 要把 body 讀乾，否則 keep-alive 連線不會回池，第二階段就退化成冷啟動。
		_, _ = io.Copy(io.Discard, resp.Body)
		_ = resp.Body.Close()
		if resp.StatusCode >= 400 {
			return E.New("unexpected status: ", resp.Status)
		}
		return nil
	}
	doHead := func() error {
		err := doProbe()
		if err == nil || probeMethod == http.MethodGet || ctx.Err() != nil {
			return err
		}
		probeMethod = http.MethodGet
		return doProbe()
	}

	// 第一次：预热（建立 TLS 会话等），不计时
	if err = doHead(); err != nil {
		boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest warmup request failed: %v", err))
		return 0, err
	}
	// 第二次：复用连接，纯 RTT 计时
	start := time.Now()
	if err = doHead(); err != nil {
		boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest measure request failed after %dms: %v", time.Since(start).Milliseconds(), err))
		return 0, err
	}
	latency := int32(time.Since(start).Milliseconds())
	boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest ok latency=%dms", latency))
	return latency, nil
}

// urlTestDirect 为无 box 实例时的直连测速：单 GET，计时含拨号。
func urlTestDirect(client *http.Client, link string) (int32, error) {
	req, err := http.NewRequest(http.MethodGet, link, nil)
	if err != nil {
		boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest new request failed: %v", err))
		return 0, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest request failed after %dms: %v", time.Since(start).Milliseconds(), err))
		return 0, err
	}
	_ = resp.Body.Close()
	if resp.StatusCode >= 400 {
		boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest unexpected status: %s", resp.Status))
		return 0, E.New("unexpected status: ", resp.Status)
	}
	latency := int32(time.Since(start).Milliseconds())
	boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.urlTest ok status=%s latency=%dms", resp.Status, latency))
	return latency, nil
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
