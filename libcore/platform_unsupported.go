package libcore

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	"net/netip"
	"os"
)

type platformUnsupportedInterface struct{}

func (s *platformUnsupportedInterface) Initialize(networkManager adapter.NetworkManager) error {
	return nil
}

func (s *platformUnsupportedInterface) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (s *platformUnsupportedInterface) AutoDetectInterfaceControl(fd int) error {
	return nil
}

func (s *platformUnsupportedInterface) UsePlatformInterface() bool {
	return false
}

func (s *platformUnsupportedInterface) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	return nil, os.ErrInvalid
}

func (s *platformUnsupportedInterface) ProcessPlatformOptions(options option.TunPlatformOptions) error {
	return nil
}

func (s *platformUnsupportedInterface) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (s *platformUnsupportedInterface) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

func (s *platformUnsupportedInterface) UsePlatformNetworkInterfaces() bool {
	return false
}

func (s *platformUnsupportedInterface) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, os.ErrInvalid
}

func (s *platformUnsupportedInterface) UnderNetworkExtension() bool {
	return false
}

func (s *platformUnsupportedInterface) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (s *platformUnsupportedInterface) ClearDNSCache() {
}

func (s *platformUnsupportedInterface) RequestPermissionForWIFIState() error {
	return nil
}

func (s *platformUnsupportedInterface) UsePlatformWIFIMonitor() bool {
	return false
}

func (s *platformUnsupportedInterface) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	return adapter.WIFIState{}
}

func (s *platformUnsupportedInterface) UsePlatformConnectionOwnerFinder() bool {
	return false
}

func (s *platformUnsupportedInterface) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (s *platformUnsupportedInterface) UsePlatformNotification() bool {
	return false
}

func (s *platformUnsupportedInterface) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (s *platformUnsupportedInterface) CancelNotification(identifier string, typeID int32) error {
	return nil
}

func (s *platformUnsupportedInterface) MyInterfaceAddress() []netip.Addr {
	return nil
}

func (s *platformUnsupportedInterface) UsePlatformNeighborResolver() bool {
	return false
}

func (s *platformUnsupportedInterface) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return os.ErrInvalid
}

func (s *platformUnsupportedInterface) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (s *platformUnsupportedInterface) UsePlatformShell() bool {
	return false
}

func (s *platformUnsupportedInterface) CheckPlatformShell() error {
	return nil
}

func (s *platformUnsupportedInterface) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}

func (s *platformUnsupportedInterface) LookupSFTPServer() (string, error) {
	return "", os.ErrInvalid
}

func (s *platformUnsupportedInterface) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, os.ErrInvalid
}

func (s *platformUnsupportedInterface) TailscaleHostname() string {
	return ""
}

func (s *platformUnsupportedInterface) UsePlatformBridge() bool {
	return false
}

func (s *platformUnsupportedInterface) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, os.ErrInvalid
}

func (s *platformUnsupportedInterface) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, os.ErrInvalid
}
