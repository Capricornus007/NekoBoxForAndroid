module libcore

go 1.26.6

require (
	// v0.1.6签名与 v1.13.15 锁定版一致。
	github.com/exclavenetwork/sing-juicity v0.3.0-beta.1
	github.com/gofrs/uuid/v5 v5.5.1
	github.com/miekg/dns v1.1.72
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/sagernet/quic-go v0.61.0-sing-box-mod.5
	github.com/sagernet/sing v0.9.0-beta.4
	github.com/sagernet/sing-box v1.0.0 // replaced
	github.com/sagernet/sing-tun v0.8.12-0.20260810140529-d67734281390
	github.com/ulikunitz/xz v0.5.15
	golang.org/x/mobile v0.0.0-20240520174638-fa72addaaa1b
	golang.org/x/sys v0.47.0
)

require (
	github.com/exclavenetwork/sing-shadowquic v0.0.0-20260801175020-65c7acc31f93
	github.com/xchacha20-poly1305/sing-trusttunnel v0.3.0-beta.5
	golang.org/x/net v0.57.0
)

require (
	github.com/ajg/form v1.7.1 // indirect
	github.com/amnezia-vpn/amneziawg-go v1.0.4 // indirect
	github.com/andybalholm/brotli v1.1.1 // indirect
	github.com/andybalholm/cascadia v1.3.4 // indirect
	github.com/antchfx/htmlquery v1.3.6 // indirect
	github.com/antchfx/xpath v1.3.8 // indirect
	github.com/anytls/sing-anytls v0.0.13 // indirect
	github.com/caddyserver/certmagic v0.25.3-0.20260421143802-60d9d8b415d6 // indirect
	github.com/caddyserver/zerossl v0.1.5 // indirect
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/cretz/bine v0.2.0 // indirect
	github.com/database64128/netx-go v0.1.1 // indirect
	github.com/database64128/tfo-go/v2 v2.3.2 // indirect
	github.com/ebitengine/purego v0.10.0 // indirect
	github.com/florianl/go-nfqueue/v2 v2.1.0 // indirect
	github.com/fsnotify/fsnotify v1.9.0 // indirect
	github.com/go-chi/chi/v5 v5.2.5 // indirect
	github.com/go-chi/render v1.0.3 // indirect
	github.com/go-ole/go-ole v1.3.0 // indirect
	github.com/gobwas/httphead v0.1.0 // indirect
	github.com/gobwas/pool v0.2.1 // indirect
	github.com/goccy/go-json v0.10.6 // indirect
	github.com/godbus/dbus/v5 v5.2.2 // indirect
	github.com/golang/groupcache v0.0.0-20241129210726-2c02b8208cf8 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/google/go-cmp v0.7.0 // indirect
	github.com/google/gopacket v1.1.19 // indirect
	github.com/hashicorp/yamux v0.1.2 // indirect
	github.com/huin/goupnp v1.3.0 // indirect
	github.com/jackpal/go-nat-pmp v1.0.2 // indirect
	github.com/jsimonetti/rtnetlink v1.4.1 // indirect
	github.com/klauspost/compress v1.19.1 // indirect
	github.com/klauspost/cpuid/v2 v2.3.0 // indirect
	github.com/koron/go-ssdp v0.0.4 // indirect
	github.com/libdns/acmedns v0.5.0 // indirect
	github.com/libdns/alidns v1.0.6 // indirect
	github.com/libdns/cloudflare v0.2.2 // indirect
	github.com/libdns/libdns v1.1.1 // indirect
	github.com/libp2p/go-nat v1.0.1-0.20250821073202-01afc089f138 // indirect
	github.com/libp2p/go-netroute v0.2.1 // indirect
	github.com/logrusorgru/aurora v2.0.3+incompatible // indirect
	github.com/mdlayher/netlink v1.11.2 // indirect
	github.com/mdlayher/socket v0.6.0 // indirect
	github.com/metacubex/cpu v0.1.0 // indirect
	github.com/metacubex/hkdf v0.1.0 // indirect
	github.com/metacubex/hpke v0.1.0 // indirect
	github.com/metacubex/jls-quic-go v0.0.0-20260727080412-732f2fc9a34d // indirect
	github.com/metacubex/jls-tls v0.0.0-20260723084315-67adc0e2f796 // indirect
	github.com/metacubex/mlkem v0.1.0 // indirect
	github.com/metacubex/randv2 v0.2.1-0.20260726125100-81aa96a9b1a5 // indirect
	github.com/metacubex/utls v1.8.7 // indirect
	github.com/mholt/acmez/v3 v3.1.6 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/sagernet/bbolt v0.0.0-20260821040940-d7518b45c52b // indirect
	github.com/sagernet/cors v1.2.1 // indirect
	github.com/sagernet/cronet-go v0.0.0-20260807162344-ec9a39c5ba3b // indirect
	github.com/sagernet/cronet-go/all v0.0.0-20260807162344-ec9a39c5ba3b // indirect
	github.com/sagernet/cronet-go/lib/android_386 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/android_amd64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/android_arm v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/android_arm64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/darwin_amd64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/darwin_arm64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/ios_amd64_simulator v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/ios_arm64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/ios_arm64_simulator v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_386 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_386_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_amd64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_amd64_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_arm v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_arm64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_arm64_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_arm_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_loong64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_loong64_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_mips64le v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_mipsle v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_mipsle_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_riscv64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/linux_riscv64_musl v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/tvos_amd64_simulator v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/tvos_arm64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/tvos_arm64_simulator v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/windows_amd64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/cronet-go/lib/windows_arm64 v0.0.0-20260807161529-8d42107dcdfc // indirect
	github.com/sagernet/fswatch v0.1.2 // indirect
	github.com/sagernet/gvisor v0.0.0-20260727.0-sing-box-mod.1 // indirect
	github.com/sagernet/netlink v0.0.0-20260814022025-64455d367bbf // indirect
	github.com/sagernet/nftables v0.3.0-mod.4 // indirect
	github.com/sagernet/sing-mux v0.3.5 // indirect
	github.com/sagernet/sing-quic v0.7.0-beta.3 // indirect
	github.com/sagernet/sing-shadowsocks v0.2.8 // indirect
	github.com/sagernet/sing-shadowsocks2 v0.2.1 // indirect
	github.com/sagernet/sing-shadowtls v0.2.1 // indirect
	github.com/sagernet/sing-usbip v0.0.0-20260817040617-28bd42667eca // indirect
	github.com/sagernet/sing-vmess v0.2.8-0.20250909125414-3aed155119a1 // indirect
	github.com/sagernet/smux v1.5.50-sing-box-mod.1 // indirect
	github.com/sagernet/wireguard-go v0.0.5-0.20260823125007-8bd032a91a30 // indirect
	github.com/sagernet/ws v0.0.0-20231204124109-acfe8907c854 // indirect
	github.com/tetratelabs/wazero v1.12.0 // indirect
	github.com/tevino/abool v1.2.0 // indirect
	github.com/vishvananda/netns v0.0.5 // indirect
	github.com/wasilibs/go-re2 v1.12.0 // indirect
	github.com/wasilibs/wazero-helpers v0.0.0-20250123031827-cd30c44769bb // indirect
	github.com/zeebo/blake3 v0.2.4 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.27.1 // indirect
	go.uber.org/zap/exp v0.3.0 // indirect
	go4.org/netipx v0.0.0-20231129151722-fdeea329fbba // indirect
	golang.org/x/crypto v0.54.0 // indirect
	golang.org/x/exp v0.0.0-20260410095643-746e56fc9e2f // indirect
	golang.org/x/mod v0.37.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/text v0.40.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.47.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20251202230838-ff82c1b0f217 // indirect
	google.golang.org/grpc v1.79.3 // indirect
	google.golang.org/protobuf v1.36.11 // indirect
	lukechampine.com/blake3 v1.3.0 // indirect
)

replace github.com/matsuridayo/libneko => ../../libneko

replace github.com/sagernet/sing-box => ../../sing-box

replace github.com/sagernet/sing-vmess => github.com/Capricornus007/sing-vmess v0.2.8-mod.1-nb4a

// sing-juicity was renamed dyhkwong -> exclavenetwork at v0.1.x; the import path
// in libcore stays dyhkwong. v0.1.4 targets quic-go v0.59 / sing v0.8 / sing-quic v0.6.1.
replace github.com/dyhkwong/sing-juicity => github.com/exclavenetwork/sing-juicity v0.1.4

replace github.com/sagernet/sing-snell => github.com/reF1nd/sing-snell v0.0.0-20260713132549-9711346e2b35

replace github.com/sagernet/wireguard-go => ../../wireguard-go

// replace github.com/sagernet/sing-quic => github.com/matsuridayo/sing-quic v0.0.0-20241009042333-b49ce60d9b36
replace github.com/sagernet/sing-quic => ../../sing-quic

// replace github.com/sagernet/sing => ../../sing

// replace github.com/sagernet/sing-dns => ../../sing-dns

// replace berty.tech/go-libtor => github.com/berty/go-libtor v0.0.0-20220627102132-9189eb6e3982

// sing-quic: SagerNet 版には sing-box 1.14.x が必要とする realm PortMapping が含まれる。
// replace github.com/sagernet/sing-quic => github.com/hawkff/sing-quic v0.6.2-0.20260630192917-42e8810bc4b8
