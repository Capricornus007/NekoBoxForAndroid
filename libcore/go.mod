module libcore

go 1.28

require (
	// 与 Throne 2e7182b9ea99947a409fee30f74df83752ab763c 的测速基线一致。
	github.com/Mahdi-zarei/speedtest-go v1.7.13-0.20260107171856-79c565dfd83a
	// v0.1.6签名与 v1.13.15 锁定版一致。
	github.com/exclavenetwork/sing-juicity v0.3.0-beta.1
	github.com/exclavenetwork/sing-shadowquic v0.0.0-20260801175020-65c7acc31f93
	github.com/gofrs/uuid/v5 v5.5.1
	github.com/matsuridayo/libneko v0.0.0-00010101000000-000000000000
	github.com/miekg/dns v1.1.73
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/sagernet/quic-go v0.61.0-sing-box-mod.12
	github.com/sagernet/sing v0.9.7-0.20261022215834-56e8e9fb54a4
	github.com/sagernet/sing-box v1.0.0 // replaced
	github.com/sagernet/sing-snell v0.0.0-20260904135315-bc5a12ac736f
	github.com/sagernet/sing-tun v0.9.7-0.20261022224154-1af57d7f3a23
	github.com/sagernet/tailscale v1.102.1-sing-box-1.14-mod.5.0.20260925112514-35e61219dedd
	github.com/ulikunitz/xz v0.5.15
	github.com/xchacha20-poly1305/sing-trusttunnel v0.3.0-beta.6
	golang.org/x/mobile v0.0.0-20261011034528-197770444867
	golang.org/x/net v0.61.0
	golang.org/x/sys v0.49.0
)

require (
	filippo.io/edwards25519 v1.2.0 // indirect
	github.com/RyuaNerin/go-krypto v1.3.0 // indirect
	github.com/ajg/form v1.9.0 // indirect
	github.com/akutz/memconn v0.1.0 // indirect
	github.com/alexbrainman/sspi v0.0.0-20250919150558-7d374ff0d59e // indirect
	github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828 // indirect
	github.com/anchore/go-lzo v0.1.1 // indirect
	github.com/andybalholm/brotli v1.2.6 // indirect
	github.com/andybalholm/cascadia v1.3.5 // indirect
	github.com/anmitsu/go-shlex v0.0.0-20200514113438-38f4b401e2be // indirect
	github.com/antchfx/htmlquery v1.3.7 // indirect
	github.com/antchfx/xpath v1.3.9 // indirect
	github.com/caddyserver/certmagic v0.25.6 // indirect
	github.com/caddyserver/zerossl v0.1.6 // indirect
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/coder/websocket v1.8.15 // indirect
	github.com/coreos/go-iptables v0.8.0 // indirect
	github.com/creack/pty v1.1.24 // indirect
	github.com/cretz/bine v0.2.0 // indirect
	github.com/database64128/netx-go v0.1.1 // indirect
	github.com/database64128/tfo-go/v2 v2.4.2 // indirect
	github.com/dblohm7/wingoes v0.0.0-20260526185140-fb298caac7ca // indirect
	github.com/dgryski/go-camellia v0.0.0-20191119043421-69a8a13fb23d // indirect
	github.com/ebitengine/purego v0.11.2 // indirect
	github.com/florianl/go-nfqueue/v2 v2.1.0 // indirect
	github.com/fsnotify/fsnotify v1.10.1 // indirect
	github.com/fxamacker/cbor/v2 v2.9.6 // indirect
	github.com/gaissmai/bart v0.30.0 // indirect
	github.com/go-chi/chi/v5 v5.5.0 // indirect
	github.com/go-chi/render v1.0.3 // indirect
	github.com/go-ole/go-ole v1.3.0 // indirect
	github.com/gobwas/httphead v0.1.0 // indirect
	github.com/gobwas/pool v0.2.1 // indirect
	github.com/goccy/go-json v0.11.2 // indirect
	github.com/godbus/dbus/v5 v5.2.4 // indirect
	github.com/golang/groupcache v0.0.0-20241129210726-2c02b8208cf8 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/google/certificate-transparency-go v1.4.0 // indirect
	github.com/google/go-cmp v0.7.0 // indirect
	github.com/google/nftables v0.3.0 // indirect
	github.com/hashicorp/yamux v0.1.2 // indirect
	github.com/hdevalence/ed25519consensus v0.2.0 // indirect
	github.com/huin/goupnp v1.3.0 // indirect
	github.com/jackpal/go-nat-pmp v1.1.0 // indirect
	github.com/jsimonetti/rtnetlink v1.4.2 // indirect
	github.com/klauspost/compress v1.20.3 // indirect
	github.com/klauspost/cpuid/v2 v2.4.1 // indirect
	github.com/koron/go-ssdp v0.9.1 // indirect
	github.com/kr/fs v0.1.0 // indirect
	github.com/libdns/acmedns v0.5.0 // indirect
	github.com/libdns/alidns v1.0.7 // indirect
	github.com/libdns/cloudflare v0.2.2 // indirect
	github.com/libdns/libdns v1.1.1 // indirect
	github.com/libp2p/go-nat v1.0.1-0.20250821073202-01afc089f138 // indirect
	github.com/libp2p/go-netroute v0.4.0 // indirect
	github.com/logrusorgru/aurora v2.0.3+incompatible // indirect
	github.com/mdlayher/netlink v1.11.2 // indirect
	github.com/mdlayher/socket v0.8.0 // indirect
	github.com/metacubex/cpu v0.1.0 // indirect
	github.com/metacubex/hkdf v0.1.0 // indirect
	github.com/metacubex/hpke v0.1.0 // indirect
	github.com/metacubex/jls-quic-go v0.0.0-20260727080412-732f2fc9a34d // indirect
	github.com/metacubex/jls-tls v0.0.0-20260723084315-67adc0e2f796 // indirect
	github.com/metacubex/mlkem v0.1.0 // indirect
	github.com/metacubex/randv2 v0.2.1-0.20260726125100-81aa96a9b1a5 // indirect
	github.com/metacubex/utls v1.8.8 // indirect
	github.com/mholt/acmez/v3 v3.1.7 // indirect
	github.com/mitchellh/go-ps v1.0.0 // indirect
	github.com/pierrec/lz4/v4 v4.1.33 // indirect
	github.com/pires/go-proxyproto v0.16.0 // indirect
	github.com/pkg/sftp v1.13.11 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/safchain/ethtool v0.7.0 // indirect
	github.com/sagernet/bbolt v0.0.0-20260915102804-500ee1e84832 // indirect
	github.com/sagernet/cors v1.2.1 // indirect
	github.com/sagernet/cronet-go v0.0.0-20260929213745-4d18a60dc3a8 // indirect
	github.com/sagernet/cronet-go/all v0.0.0-20260929213745-4d18a60dc3a8 // indirect
	github.com/sagernet/cronet-go/lib/android_386 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/android_amd64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/android_arm v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/android_arm64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/darwin_amd64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/darwin_arm64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/ios_amd64_simulator v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/ios_arm64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/ios_arm64_simulator v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_386 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_386_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_amd64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_amd64_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_arm v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_arm64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_arm64_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_arm_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_loong64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_loong64_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_mips64le v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_mipsle v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_mipsle_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_riscv64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/linux_riscv64_musl v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/tvos_amd64_simulator v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/tvos_arm64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/tvos_arm64_simulator v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/windows_amd64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/cronet-go/lib/windows_arm64 v0.0.0-20260929213014-a1cafd93eb1f // indirect
	github.com/sagernet/fswatch v0.1.2 // indirect
	github.com/sagernet/gliderssh v0.3.4-0.20260531100337-2194faca5648 // indirect
	github.com/sagernet/gvisor v0.0.0-20260727.0-sing-box-mod.1 // indirect
	github.com/sagernet/netlink v0.0.0-20260919000536-5d76abd97cf7 // indirect
	github.com/sagernet/nftables v0.3.0-mod.4 // indirect
	github.com/sagernet/sing-anytls v0.0.0-20260928104022-580984e4d8cb // indirect
	github.com/sagernet/sing-mux v0.3.10-0.20260929204512-caf09fe32475 // indirect
	github.com/sagernet/sing-openvpn v0.1.1-0.20260929151220-f330676d6d4a // indirect
	github.com/sagernet/sing-quic v0.7.2-0.20261020082049-7e0a5869a5f4 // indirect
	github.com/sagernet/sing-shadowsocks v0.2.9-0.20260929204512-65740e0f0e3e // indirect
	github.com/sagernet/sing-shadowsocks2 v0.2.2-0.20260929152114-a69d1086332b // indirect
	github.com/sagernet/sing-shadowtls v0.2.2-0.20260928201441-a9c0127d5c99 // indirect
	github.com/sagernet/sing-usbip v0.0.0-20260817040617-28bd42667eca // indirect
	github.com/sagernet/sing-vmess v0.2.9-0.20260929152519-9b95ab8c9478 // indirect
	github.com/sagernet/smux v1.5.50-sing-box-mod.1 // indirect
	github.com/sagernet/wireguard-go v0.0.8-0.20261022215849-fdf0ad046586 // indirect
	github.com/sagernet/ws v0.0.0-20231204124109-acfe8907c854 // indirect
	github.com/tailscale/certstore v0.1.1-0.20260409135935-3638fb84b77d // indirect
	github.com/tailscale/go-winio v0.0.0-20231025203758-c4f33415bf55 // indirect
	github.com/tailscale/hujson v0.0.0-20260727124030-b80ff77dac4f // indirect
	github.com/tailscale/netlink v1.1.1-0.20240822203006-4d49adab4de7 // indirect
	github.com/tailscale/peercred v0.0.0-20250107143737-35a0c7bd7edc // indirect
	github.com/tailscale/web-client-prebuilt v0.0.0-20260917222731-e0ed2d0d0fea // indirect
	github.com/tjfoc/gmsm v1.4.1 // indirect
	github.com/vishvananda/netns v0.0.5 // indirect
	github.com/wasilibs/go-re2 v1.13.0 // indirect
	github.com/x448/float16 v0.8.4 // indirect
	github.com/zeebo/blake3 v0.2.4 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.28.0 // indirect
	go.uber.org/zap/exp v0.3.0 // indirect
	go4.org/mem v0.0.0-20240501181205-ae6ca9944745 // indirect
	go4.org/netipx v0.0.0-20260823151212-3075585bcbeb // indirect
	golang.org/x/crypto v0.58.0 // indirect
	golang.org/x/exp v0.0.0-20261014143641-f0251eb4aa3e // indirect
	golang.org/x/mod v0.42.0 // indirect
	golang.org/x/oauth2 v0.37.0 // indirect
	golang.org/x/sync v0.24.0 // indirect
	golang.org/x/term v0.47.0 // indirect
	golang.org/x/text v0.43.0 // indirect
	golang.org/x/time v0.16.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	golang.zx2c4.com/wireguard/windows v1.1.1 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20261019180522-ce908d29299b // indirect
	google.golang.org/grpc v1.84.0 // indirect
	google.golang.org/protobuf v1.36.12 // indirect
	lukechampine.com/blake3 v1.4.1 // indirect
)

replace github.com/matsuridayo/libneko => github.com/Capricornus007/libneko v0.0.0-20261011114030-1dad84547be5

replace github.com/amnezia-vpn/amneziawg-go/v3 => github.com/Capricornus007/amneziawg-go/v3 v3.1.20261008-mod.5

replace github.com/sagernet/sing-box => ../../sing-box

replace github.com/sagernet/sing-vmess => github.com/Capricornus007/sing-vmess v0.2.8-mod.1-nb4a.0.20261023034519-134c8fa39a32

// sing-juicity was renamed dyhkwong -> exclavenetwork at v0.1.x; the import path
// in libcore stays dyhkwong. v0.1.4 targets quic-go v0.59 / sing v0.8 / sing-quic v0.6.1.
replace github.com/dyhkwong/sing-juicity => github.com/exclavenetwork/sing-juicity v0.1.4

replace github.com/sagernet/sing-snell => github.com/Capricornus007/sing-snell v0.0.0-20261023034450-2a45f12a2599

replace github.com/sagernet/wireguard-go => ../../wireguard-go

// replace github.com/sagernet/sing-quic => github.com/matsuridayo/sing-quic v0.0.0-20241009042333-b49ce60d9b36
replace github.com/sagernet/sing-quic => github.com/Capricornus007/sing-quic v0.7.0-beta.2.0.20261023034421-e8d980d7061e

// replace github.com/sagernet/sing => ../../sing

// replace github.com/sagernet/sing-dns => ../../sing-dns

// replace berty.tech/go-libtor => github.com/berty/go-libtor v0.0.0-20220627102132-9189eb6e3982

// sing-quic: SagerNet 版には sing-box 1.14.x が必要とする realm PortMapping が含まれる。
// replace github.com/sagernet/sing-quic => github.com/hawkff/sing-quic v0.6.2-0.20260630192917-42e8810bc4b8

replace github.com/sagernet/sing-tun => github.com/Capricornus007/sing-tun v0.9.6-sing-box-fork.0.20261024002847-98846c220bf4

replace github.com/sagernet/quic-go => github.com/Capricornus007/quic-go v0.61.0-sing-box-mod.9.0.20261023034407-c7b0124a13f8

replace github.com/sagernet/sing-mux => github.com/Capricornus007/sing-mux v0.3.7-0.20261023034436-b5ac5e2ea4b4

replace github.com/metacubex/utls => github.com/Capricornus007/utls v1.8.8-0.20261023032810-2c28a526ca61

replace github.com/exclavenetwork/sing-juicity => ../../sing-juicity

replace github.com/xchacha20-poly1305/sing-trusttunnel => ../../sing-trusttunnel

replace github.com/sagernet/sing => github.com/Capricornus007/sing v0.9.5-0.20261023032553-61708a50b695

// sing-shadowquic 復刻到自有 fork（與 sing 家族其他包一致），跟 Capricornus007/sing-shadowquic main。
replace github.com/exclavenetwork/sing-shadowquic => github.com/Capricornus007/sing-shadowquic v0.0.0-20260904152941-03a261e772e4

// 與 sing-box/go.mod 保持同一條：v3 的 amneziawg 會拉 gvisor.dev/gvisor，
// 我方實際用的是 Capricornus007/gvisor-awg（AWG 改過的那版）；
// replace 只在主模組生效，libcore 這個主模組必須自己寫一遍。
replace gvisor.dev/gvisor => github.com/Capricornus007/gvisor-awg v0.0.0-20261023032907-eaadebffb9ac

// 同一個理由：libcore/tailscale*.go 直接 import github.com/sagernet/tailscale/{ipn,net/tsaddr,net/netmon}，
// 而 sing-box 已把該模組換成我方 fork。少了這條，APK 裡的 tailscale 就會編上游原版，
// 跟 sing-box/protocol/tailscale 測過的不是同一份代碼。
replace github.com/sagernet/tailscale => github.com/Capricornus007/tailscale v1.102.1-sing-box-1.14-mod.5.0.20261023034533-b3c1784682e2
