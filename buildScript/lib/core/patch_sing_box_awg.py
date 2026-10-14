#!/usr/bin/env python3
import pathlib
import sys


def patch_file(path: pathlib.Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise RuntimeError(f"anchor not found in {path}")
    path.write_text(text.replace(old, new, 1))


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_sing_box_awg.py <sing-box-dir>")
    root = pathlib.Path(sys.argv[1]).resolve()

    # 1) Add AmneziaWG fields to wireguard outbound options
    wireguard_option = root / "option" / "wireguard.go"
    patch_file(
        wireguard_option,
        """\tWorkers       int         `json:"workers,omitempty"`\n\tMTU           uint32      `json:"mtu,omitempty"`\n\tNetwork       NetworkList `json:"network,omitempty"`\n}\n""",
        """\tWorkers       int         `json:"workers,omitempty"`\n\tMTU           uint32      `json:"mtu,omitempty"`\n\tNetwork       NetworkList `json:"network,omitempty"`\n\tJc            int         `json:"jc,omitempty"`\n\tJmin          int         `json:"jmin,omitempty"`\n\tJmax          int         `json:"jmax,omitempty"`\n\tS1            int         `json:"s1,omitempty"`\n\tS2            int         `json:"s2,omitempty"`\n\tS3            int         `json:"s3,omitempty"`\n\tS4            int         `json:"s4,omitempty"`\n\tH1            string      `json:"h1,omitempty"`\n\tH2            string      `json:"h2,omitempty"`\n\tH3            string      `json:"h3,omitempty"`\n\tH4            string      `json:"h4,omitempty"`\n\tI1            string      `json:"i1,omitempty"`\n\tI2            string      `json:"i2,omitempty"`\n\tI3            string      `json:"i3,omitempty"`\n\tI4            string      `json:"i4,omitempty"`\n\tI5            string      `json:"i5,omitempty"`\n}\n""",
    )

    endpoint_options = root / "transport" / "wireguard" / "endpoint_options.go"
    patch_file(
        endpoint_options,
        """\tPeers        []PeerOptions\n\tWorkers      int\n}\n""",
        """\tPeers        []PeerOptions\n\tWorkers      int\n\tJc           int\n\tJmin         int\n\tJmax         int\n\tS1           int\n\tS2           int\n\tS3           int\n\tS4           int\n\tH1           string\n\tH2           string\n\tH3           string\n\tH4           string\n\tI1           string\n\tI2           string\n\tI3           string\n\tI4           string\n\tI5           string\n}\n""",
    )

    protocol_outbound = root / "protocol" / "wireguard" / "outbound.go"
    patch_file(
        protocol_outbound,
        """\t\tPeers:   peers,\n\t\tWorkers: options.Workers,\n\t})\n""",
        """\t\tPeers:   peers,\n\t\tWorkers: options.Workers,\n\t\tJc:      options.Jc,\n\t\tJmin:    options.Jmin,\n\t\tJmax:    options.Jmax,\n\t\tS1:      options.S1,\n\t\tS2:      options.S2,\n\t\tS3:      options.S3,\n\t\tS4:      options.S4,\n\t\tH1:      options.H1,\n\t\tH2:      options.H2,\n\t\tH3:      options.H3,\n\t\tH4:      options.H4,\n\t\tI1:      options.I1,\n\t\tI2:      options.I2,\n\t\tI3:      options.I3,\n\t\tI4:      options.I4,\n\t\tI5:      options.I5,\n\t})\n""",
    )

    # The modern endpoint path needs the same AWG fields as legacy outbounds.
    patch_file(
        wireguard_option,
        "\tWorkers    int                              `json:\"workers,omitempty\"`\n\tDialerOptions\n}",
        "\tWorkers    int                              `json:\"workers,omitempty\"`\n" +
        "".join(f"\t{name} int `json:\"{name.lower()},omitempty\"`\n" for name in ["Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4"]) +
        "".join(f"\t{name} string `json:\"{name.lower()},omitempty\"`\n" for name in ["H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5"]) + "\tDialerOptions\n}",
    )
    patch_file(
        root / "protocol" / "wireguard" / "endpoint.go",
        "\t\tWorkers: options.Workers,\n\t})",
        "\t\tWorkers: options.Workers,\n" +
        "".join(f"\t\t{name}: options.{name},\n" for name in ["Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5"]) + "\t})",
    )

    protocol_init = root / "protocol" / "wireguard" / "init.go"
    patch_file(
        wireguard_option,
        "type LegacyWireGuardPeer struct {\n\tServerOptions\n",
        "type LegacyWireGuardPeer struct {\n\tServerOptions\n\tPersistentKeepaliveInterval uint16 `json:\"persistent_keepalive_interval,omitempty\"`\n",
    )
    patch_file(
        root / "protocol" / "wireguard" / "outbound.go",
        "\t\t\t// PersistentKeepaliveInterval: time.Duration(it.PersistentKeepaliveInterval),",
        "\t\t\tPersistentKeepaliveInterval: it.PersistentKeepaliveInterval,",
    )
    patch_file(
        protocol_init,
        """\t"github.com/sagernet/wireguard-go/conn"\n""",
        "",
    )
    patch_file(
        protocol_init,
        """func init() {\n\tdialer.WgControlFns = conn.ControlFns\n}\n""",
        """func init() {\n\tdialer.WgControlFns = nil\n}\n""",
    )

    endpoint = root / "transport" / "wireguard" / "endpoint.go"
    patch_file(
        endpoint,
        """\t"github.com/sagernet/wireguard-go/conn"\n""",
        "",
    )
    patch_file(
        endpoint,
        """\tipcConf := "private_key=" + privateKey\n\tif options.ListenPort != 0 {\n\t\tipcConf += "\\nlisten_port=" + F.ToString(options.ListenPort)\n\t}\n""",
        """\tipcConf := "private_key=" + privateKey\n\tif options.ListenPort != 0 {\n\t\tipcConf += "\\nlisten_port=" + F.ToString(options.ListenPort)\n\t}\n\tif options.Jc > 0 {\n\t\tipcConf += "\\njc=" + F.ToString(options.Jc)\n\t}\n\tif options.Jmin > 0 {\n\t\tipcConf += "\\njmin=" + F.ToString(options.Jmin)\n\t}\n\tif options.Jmax > 0 {\n\t\tipcConf += "\\njmax=" + F.ToString(options.Jmax)\n\t}\n\tif options.S1 > 0 {\n\t\tipcConf += "\\ns1=" + F.ToString(options.S1)\n\t}\n\tif options.S2 > 0 {\n\t\tipcConf += "\\ns2=" + F.ToString(options.S2)\n\t}\n\tif options.S3 > 0 {\n\t\tipcConf += "\\ns3=" + F.ToString(options.S3)\n\t}\n\tif options.S4 > 0 {\n\t\tipcConf += "\\ns4=" + F.ToString(options.S4)\n\t}\n\tif options.H1 != "" {\n\t\tipcConf += "\\nh1=" + options.H1\n\t}\n\tif options.H2 != "" {\n\t\tipcConf += "\\nh2=" + options.H2\n\t}\n\tif options.H3 != "" {\n\t\tipcConf += "\\nh3=" + options.H3\n\t}\n\tif options.H4 != "" {\n\t\tipcConf += "\\nh4=" + options.H4\n\t}\n\tif options.I1 != "" {\n\t\tipcConf += "\\ni1=" + options.I1\n\t}\n\tif options.I2 != "" {\n\t\tipcConf += "\\ni2=" + options.I2\n\t}\n\tif options.I3 != "" {\n\t\tipcConf += "\\ni3=" + options.I3\n\t}\n\tif options.I4 != "" {\n\t\tipcConf += "\\ni4=" + options.I4\n\t}\n\tif options.I5 != "" {\n\t\tipcConf += "\\ni5=" + options.I5\n\t}\n""",
    )
    patch_file(
        endpoint,
        """\tvar bind conn.Bind\n\twgListener, isWgListener := common.Cast[conn.Listener](e.options.Dialer)\n\tif isWgListener {\n\t\tbind = conn.NewStdNetBind(wgListener)\n\t} else {\n\t\tvar (\n\t\t\tisConnect   bool\n\t\t\tconnectAddr netip.AddrPort\n\t\t\treserved    [3]uint8\n\t\t)\n\t\tif len(e.peers) == 1 && e.peers[0].endpoint.IsValid() {\n\t\t\tisConnect = true\n\t\t\tconnectAddr = e.peers[0].endpoint\n\t\t\treserved = e.peers[0].reserved\n\t\t}\n\t\tbind = NewClientBind(e.options.Context, e.options.Logger, e.options.Dialer, isConnect, connectAddr, reserved)\n\t}\n\tif isWgListener || len(e.peers) > 1 {\n\t\tfor _, peer := range e.peers {\n\t\t\tif peer.reserved != [3]uint8{} {\n\t\t\t\tbind.SetReservedForEndpoint(peer.endpoint, peer.reserved)\n\t\t\t}\n\t\t}\n\t}\n""",
        """\tvar (\n\t\tisConnect   bool\n\t\tconnectAddr netip.AddrPort\n\t\treserved    [3]uint8\n\t)\n\tif len(e.peers) == 1 && e.peers[0].endpoint.IsValid() {\n\t\tisConnect = true\n\t\tconnectAddr = e.peers[0].endpoint\n\t\treserved = e.peers[0].reserved\n\t}\n\tbind := NewClientBind(e.options.Context, e.options.Logger, e.options.Dialer, isConnect, connectAddr, reserved)\n\tif len(e.peers) > 1 {\n\t\tfor _, peer := range e.peers {\n\t\t\tif peer.reserved != [3]uint8{} {\n\t\t\t\tbind.SetReservedForEndpoint(peer.endpoint, peer.reserved)\n\t\t\t}\n\t\t}\n\t}\n""",
    )
    patch_file(
        endpoint,
        """\twgDevice := device.NewDevice(e.options.Context, e.tunDevice, bind, logger, e.options.Workers)\n""",
        """\twgDevice := device.NewDevice(e.tunDevice, bind, logger)\n""",
    )

    patch_file(
        endpoint,
        "func NewEndpoint(options EndpointOptions) (*Endpoint, error) {\n",
        "func NewEndpoint(options EndpointOptions) (*Endpoint, error) {\n\tif err := validateAmneziaOptions(options); err != nil { return nil, err }\n",
    )
    (root / "transport" / "wireguard" / "amnezia_options.go").write_text('''package wireguard

import (
    "fmt"
    "strings"
)

func validateAmneziaOptions(options EndpointOptions) error {
    for name, value := range map[string]int{"Jc": options.Jc, "Jmin": options.Jmin, "Jmax": options.Jmax, "S1": options.S1, "S2": options.S2, "S3": options.S3, "S4": options.S4} {
        if value < 0 { return fmt.Errorf("%s must be non-negative", name) }
    }
    if options.Jc > 0 && options.Jmin > options.Jmax { return fmt.Errorf("Jmin must not exceed Jmax when junk packets are enabled") }
    for name, value := range map[string]string{"H1": options.H1, "H2": options.H2, "H3": options.H3, "H4": options.H4, "I1": options.I1, "I2": options.I2, "I3": options.I3, "I4": options.I4, "I5": options.I5} {
        if strings.ContainsAny(value, "\\r\\n") { return fmt.Errorf("%s must be a single line", name) }
    }
    if amneziaPacketLayout(options) {
        for _, peer := range options.Peers {
            for _, value := range peer.Reserved {
                if value != 0 { return fmt.Errorf("WireGuard reserved bytes cannot be combined with AWG packet headers or signatures") }
            }
        }
    }
    return nil
}

func amneziaPacketLayout(options EndpointOptions) bool {
    return options.S1 != 0 || options.S2 != 0 || options.S3 != 0 || options.S4 != 0 ||
        options.H1 != "" || options.H2 != "" || options.H3 != "" || options.H4 != "" ||
        options.I1 != "" || options.I2 != "" || options.I3 != "" || options.I4 != "" || options.I5 != ""
}
''')

    # AWG headers and signature packets use all bytes. WireGuard/WARP reserved
    # rewriting must not alter them in either direction.
    client_bind = root / "transport" / "wireguard" / "client_bind.go"
    patch_file(client_bind, "\treserved            [3]uint8\n", "\treserved            [3]uint8\n\tamnezia             bool\n")
    patch_file(client_bind, "\tif n > 3 {", "\tif !c.amnezia && n > 3 {")
    patch_file(client_bind, "\t\tif len(b) > 3 {", "\t\tif !c.amnezia && len(b) > 3 {")
    patch_file(endpoint,
        "\tbind := NewClientBind(e.options.Context, e.options.Logger, e.options.Dialer, isConnect, connectAddr, reserved)\n",
        "\tbind := NewClientBind(e.options.Context, e.options.Logger, e.options.Dialer, isConnect, connectAddr, reserved)\n\tbind.amnezia = amneziaPacketLayout(e.options)\n",
    )
    patch_file(endpoint, 'E.Cause(err, "setup wireguard: \\n", ipcConf)', 'E.Cause(err, "setup wireguard")')

    # A queued TUN EventUp otherwise starts AWG receive workers while IpcSet
    # is still replacing packet headers and padding parameters.
    patch_file(endpoint,
        "\twgDevice := device.NewDevice(e.tunDevice, bind, logger)\n",
        "\tconfigured := &configuredDevice{Device: e.tunDevice, ready: make(chan struct{})}\n\twgDevice := device.NewDevice(configured, bind, logger)\n",
    )
    patch_file(endpoint,
        '\terr = wgDevice.IpcSet(ipcConf)\n\tif err != nil {\n\t\treturn E.Cause(err, "setup wireguard")\n\t}\n',
        '\terr = wgDevice.IpcSet(ipcConf)\n\tif err != nil {\n\t\twgDevice.Close()\n\t\tclose(configured.ready)\n\t\treturn E.Cause(err, "setup wireguard")\n\t}\n\tclose(configured.ready)\n',
    )
    (root / "transport" / "wireguard" / "configured_device.go").write_text('''package wireguard

import wgTun "github.com/sagernet/wireguard-go/tun"

// Delay interface events until the device's initial IPC configuration is complete.
type configuredDevice struct {
    Device
    ready chan struct{}
}

func (d *configuredDevice) Events() <-chan wgTun.Event {
    <-d.ready
    return d.Device.Events()
}
''')
    # Publish and read the socket under the same lock, including the fast path.
    patch_file(client_bind,
        '\tserverConn := c.conn\n\tif serverConn != nil {\n\t\tselect {\n\t\tcase <-serverConn.done:\n\t\t\tserverConn = nil\n\t\tdefault:\n\t\t\treturn serverConn, nil\n\t\t}\n\t}\n\tc.connAccess.Lock()',
        '\tc.connAccess.Lock()',
    )
    patch_file(client_bind, '\tserverConn = c.conn\n', '\tserverConn := c.conn\n')

    device_factory = root / "transport" / "wireguard" / "device.go"
    patch_file(
        device_factory,
        "func NewDevice(options DeviceOptions) (Device, error) {\n",
        "func NewDevice(options DeviceOptions) (Device, error) {\n\tif options.System && options.Handler != nil {\n\t\treturn nil, fmt.Errorf(\"AmneziaWG system stack with a packet handler is unsupported\")\n\t}\n",
    )
    patch_file(device_factory, 'import (\n', 'import (\n\t"fmt"\n')

    # Keep with_gvisor builds compiling against amneziawg-go API
    device_system_stack = root / "transport" / "wireguard" / "device_system_stack.go"
    patch_file(
        device_system_stack,
        """\t\tdestination := packetBuffer.Network().DestinationAddress()\n\t\tep.device.InputPacket(destination.AsSlice(), packetBuffer.AsSlices())\n""",
        """\t\t_ = packetBuffer.Network().DestinationAddress()\n""",
    )


if __name__ == "__main__":
    main()
