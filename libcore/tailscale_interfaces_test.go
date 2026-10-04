package libcore

import (
	"errors"
	"net"
	"testing"
)

type fakeInterfacePlatform struct {
	BoxPlatformInterface
	list string
	err  error
}

func (f fakeInterfacePlatform) NetworkInterfaces() (string, error) { return f.list, f.err }

func TestPlatformInterfacesFromJava(t *testing.T) {
	previous := intfBox
	t.Cleanup(func() { intfBox = previous })
	intfBox = fakeInterfacePlatform{list: `[
		{"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addresses":["127.0.0.1/8","::1/128"]},
		{"name":"wlan0","index":3,"mtu":1500,"up":true,"multicast":true,"addresses":["192.0.2.10/24","fe80::1/64","bogus"]},
		{"name":"rmnet0","index":7,"mtu":1400,"pointToPoint":true,"addresses":[]}
	]`}

	interfaces, err := platformInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) != 3 {
		t.Fatalf("got %d interfaces, want 3", len(interfaces))
	}
	lo, wlan, cell := interfaces[0], interfaces[1], interfaces[2]
	if !lo.IsLoopback() || !lo.IsUp() || lo.Index != 1 || len(lo.AltAddrs) != 2 {
		t.Fatalf("loopback = %+v %v", lo.Interface, lo.AltAddrs)
	}
	if wlan.Name != "wlan0" || wlan.MTU != 1500 || !wlan.IsUp() || wlan.Flags&net.FlagMulticast == 0 {
		t.Fatalf("wlan0 = %+v", wlan.Interface)
	}
	if len(wlan.AltAddrs) != 2 || wlan.AltAddrs[0].String() != "192.0.2.10/24" || wlan.AltAddrs[1].String() != "fe80::1/64" {
		t.Fatalf("wlan0 addresses = %v", wlan.AltAddrs)
	}
	if cell.IsUp() || cell.Flags&net.FlagPointToPoint == 0 || len(cell.AltAddrs) != 0 {
		t.Fatalf("rmnet0 = %+v %v", cell.Interface, cell.AltAddrs)
	}

	intfBox = fakeInterfacePlatform{list: "not json"}
	if _, err := platformInterfaces(); err == nil {
		t.Fatal("malformed list did not fail")
	}
	intfBox = fakeInterfacePlatform{err: errors.New("enumeration failed")}
	if _, err := platformInterfaces(); err == nil || err.Error() != "enumeration failed" {
		t.Fatalf("enumeration error = %v", err)
	}
}
