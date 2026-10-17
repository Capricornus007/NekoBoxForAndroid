package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	boxlog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	_ = dialer.New
}

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
	if system {
		if mainInstance != nil {
			mainInstance.Network().ResetNetwork(mainInstance.ctx)
		}
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	candidateChecker *candidateChecker
	access           sync.Mutex
	probeWG          sync.WaitGroup
	probesPaused     bool
	probePowerCtx    context.Context
	probePowerCancel context.CancelFunc

	*box.Box
	ctx    context.Context
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
	diagnostics  *connectionDiagnostics
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })
	WaitForAssets()

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = decodeConfigOptions(ctx, []byte(config), &options)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}
	if err = materializeGeoRuleSets(&options); err != nil {
		cancel()
		return nil, fmt.Errorf("prepare geographic rules: %w", err)
	}

	// create box
	checker := newCandidateChecker(options)
	for _, outbound := range options.Outbounds {
		if test, ok := outbound.Options.(*option.URLTestOutboundOptions); ok && test.HealthCheck {
			dnsTag, link := test.HealthDNSServer, test.HealthURL
			if dnsTag == "" {
				dnsTag = "dns-remote"
			}
			if link == "" {
				link = "https://www.gstatic.com/generate_204"
			}
			if err := checker.validate(dnsTag, link); err != nil {
				cancel()
				return nil, err
			}
		}
	}
	service.MustRegister[group.CandidateHealthChecker](ctx, checker)
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:              instance,
		candidateChecker: checker,
		ctx:              ctx,
		cancel:           cancel,
		pauseManager:     service.FromContext[pause.Manager](ctx),
	}

	if (options.Log == nil || !options.Log.Disabled) && instance.LogFactory().Level() >= boxlog.LevelDebug {
		b.diagnostics = &connectionDiagnostics{}
		instance.Router().AppendTracker(b.diagnostics)
		service.MustRegister[adapter.DiagnosticObserver](ctx, b.diagnostics)
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

// CancelStart is independent of the start/close mutex so a blocked startup can be interrupted.
func (b *BoxInstance) CancelStart() {
	if b.cancel != nil {
		b.cancel()
	}
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
	b.probeWG.Wait()
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

func (b *BoxInstance) SetProbesPaused(paused bool) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.Box == nil {
		return
	}
	if b.probesPaused != paused {
		b.probesPaused = paused
		if b.probePowerCancel != nil {
			b.probePowerCancel()
		}
		if !paused {
			b.probePowerCtx, b.probePowerCancel = context.WithCancel(b.ctx)
		}
	}
	for _, outbound := range b.Outbound().Outbounds() {
		if checks, ok := outbound.(*group.URLTest); ok {
			checks.SetProbesPaused(paused)
		}
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
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
	service.MustRegister[adapter.OutboundStatsTracker](b.ctx, b.v2api.StatsService().(adapter.OutboundStatsTracker))
}

func (b *BoxInstance) QuerySessionStats(direct bool, direction string) int64 {
	if b.v2api == nil {
		return 0
	}
	category := "proxy"
	if direct {
		category = "direct"
	}
	return b.v2api.QueryStats(fmt.Sprintf("session>>>%s>>>traffic>>>%s", category, direction))
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	// test i
	if i != nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	if mainInstance == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test mainInstance
	if mainInstance.v2api != nil {
		connectionTracker = mainInstance.v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(mainInstance.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
