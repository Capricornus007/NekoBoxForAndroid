# sing-box 1.14 compatibility layer

The Android core uses SagerNet/sing-box v1.14.3, commit
`7054cac5124465ffdb145870d7c01885566711b1`, with the reviewed
`compat_114.patch` overlay. Source preparation reverses its recorded overlay
before applying it again and refuses to discard unrelated dependency edits.

The overlay retains application group policies, connection diagnostics and
traffic accounting; XHTTP, KCP, SSR, VLESS encryption and WG/AWG/AWG2 adapters;
HTTP/2 CONNECT; concurrent TCP connect policy and legacy DNS rule semantics.
Geo aliases are materialized into public inline rule-sets before core creation.
Legacy inbound/DNS JSON is normalized only in the runtime copy, with strict
validation of unknown and invalid fields. Stored configurations are unchanged.

The pinned sing-quic overlay exposes the upstream BBR constructor's initial
window argument so an explicit XHTTP CWND is retained. Juicity uses the renamed
maintained github.com/exclavenetwork/sing-juicity v0.3.1 module.

Native race tests cover live WG/AWG/AWG2, XHTTP and HTTP/2 CONNECT exchange,
DNS bootstrap/DoH/IPv6/FakeIP, UID/DNS guards, group selection, traffic,
concurrent-connect cleanup and the configured BBR window. Android arm64
bindings compile with 16 KiB ELF segment alignment.

AmneziaWG retains its existing TCP/UDP path. It does not implement upstream's
new direct L3/ICMP WireGuard port. OEM, work-profile, Private DNS and battery
checks require a device; no ADB target is available in the build environment.
