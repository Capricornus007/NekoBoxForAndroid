# sing-box 1.14 compatibility checkpoint

The default APK build still uses the tested starifly 1.12 compatibility core.
These patches preserve the candidate without selecting it for a release.

- Core base: SagerNet/sing-box v1.14.3, `7054cac5124465ffdb145870d7c01885566711b1`.
- QUIC base: SagerNet/sing-quic `75c3ac4fa12b4ded2dc01b392ed7d5eaf3ea6876`.
- Native patch base: application `6855853982cfb93a9773dffc1858ecf9e6b8771d`.
- Existing protocol adapters come from starifly/sing-box `0b7a2fc2f2c33f754e5fe5a1bb3e0a4f610b6aa9` and the application's fixes.

Reproduction: check out the two bases into the sibling `sing-box-114` and
`sing-quic-114` directories, apply `compat_114.patch` and `compat_quic_114.patch`
respectively, retain the pinned libneko/AmneziaWG siblings, then apply
`compat_114_native.patch` to the application at its native base.

The full native suite passes with race detection, including live WG/AWG/AWG2,
XHTTP, DNS bootstrap/DoH/IPv6/FakeIP, guards, traffic and group selection.
Additional core tests pass for HTTP/2 CONNECT, concurrent TCP loser cleanup and
custom BBR window preservation. Legacy inbound and DNS options are normalized
only in the runtime copy; unknown/invalid options still fail strict validation.
The renamed Juicity module is github.com/exclavenetwork/sing-juicity v0.3.1.

Android binding compilation is in progress. Activation, Java/Kotlin integration
and release checks remain open. There is no new APK at this checkpoint.
AmneziaWG retains its tested TCP/UDP path; upstream's new direct L3/ICMP
WireGuard port is not implemented. No ADB target is available, so OEM,
work-profile, Private DNS and battery checks still require device testing.
