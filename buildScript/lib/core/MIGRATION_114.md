# sing-box 1.14 compatibility layer

The Android core uses SagerNet/sing-box v1.14.3, commit
`7054cac5124465ffdb145870d7c01885566711b1`, with the reviewed
`compat_114.patch` overlay. Source preparation reverses its recorded overlay
before applying it again and refuses to discard unrelated dependency edits.

The overlay retains application group policies, connection diagnostics and
traffic accounting; XHTTP, KCP, SSR, VLESS encryption and WG/AWG/AWG2/AWG3/3.1 adapters;
HTTP/2 CONNECT; concurrent TCP connect policy and legacy DNS rule semantics.
Geo aliases are materialized into public inline rule-sets before core creation.
Legacy inbound/DNS JSON is normalized only in the runtime copy, with strict
validation of unknown and invalid fields. Stored configurations are unchanged.

The pinned sing-quic overlay exposes the upstream BBR constructor's initial
window argument so an explicit XHTTP CWND is retained. Juicity uses the renamed
maintained github.com/exclavenetwork/sing-juicity v0.3.1 module.

Native race tests cover live WG/AWG/AWG2/AWG3/3.1, XHTTP and HTTP/2 CONNECT exchange,
DNS bootstrap/DoH/IPv6/FakeIP, UID/DNS guards, group selection, traffic,
concurrent-connect cleanup and the configured BBR window. Android arm64
bindings compile with 16 KiB ELF segment alignment.

AmneziaWG retains its existing TCP/UDP path. It does not implement upstream's
new direct L3/ICMP WireGuard port. OEM, work-profile, Private DNS and battery
checks require a device; no ADB target is available in the build environment.

AmneziaWG uses the official versioned module
`github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828`
(`b5928efb6ca19f0153958460c3d141f04abc5c2e`). No dependency namespace
rewriting or local WireGuard fork replacement is needed. Both legacy outbound
and modern endpoint options expose AWG 3.1 header protection, content padding,
handshake/keepalive ranges, random trailers and cookie control. Numeric legacy
keepalive JSON and ranged string values are accepted.

The configured TUN wrapper releases its first empty read after initial IPC:
the upstream reader snapshots S4 before calling Read, so merely delaying Read
would consume the first packet with a stale offset. IPC errors release that
gate before closing the device. Live native tests cover first-packet delivery,
replies in both directions and bounded shutdown after invalid CPS input.

Android profile serialization v5 preserves v4 numeric keepalive values and
adds the AWG 3.1 fields. INI and packed Amnezia imports, cloning and editor
bindings retain those fields. HeaderProtectionKey is redacted in Kotlin and Go
diagnostics. Range/key validation errors do not include secret values.

The Android adapter provides no native default-interface monitor. Its former
no-op stub returned a nil default interface, which 1.14's post-start dispatch
interpreted as a lost network and permanently paused WireGuard. Android's
existing DefaultNetworkListener continues to own connectivity/restart handling;
VpnService fd protection is unchanged. NetworkManager.DefaultNetworkInterface
returns nil safely when the optional native monitor is absent.

The full-service regression test uses the actual Android platform adapter,
replacing only the Android fd-protection call with a counted test callback.
Both plain WG and a WARP-style AWG2 profile (1250-byte synthetic CPS preface)
exchange requests/replies after full box startup and after device pause/wake.
The test reproduced the false network pause before the fix. No uploaded
configuration, endpoint or key is included in test fixtures.
