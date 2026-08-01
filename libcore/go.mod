module libcore

go 1.24.7

require (
	github.com/dyhkwong/sing-juicity v0.0.3
	github.com/gofrs/uuid/v5 v5.4.0
	github.com/miekg/dns v1.1.72
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/sagernet/quic-go v0.59.0-sing-box-mod.4
	github.com/sagernet/sing v0.8.12-0.20260726145744-ef2df370afca
	github.com/sagernet/sing-box v1.13.15
	github.com/sagernet/sing-tun v0.8.12-0.20260727151122-3a09076491df
	github.com/ulikunitz/xz v0.5.15
	golang.org/x/mobile v0.0.0-20231108233038-35478a0c49da
	golang.org/x/net v0.50.0
	golang.org/x/sys v0.41.0
)

// 官方内核：构建时由 buildScript/lib/core/get_source.sh 按 nb4a.properties 的
// SINGBOX_VERSION 克隆 SagerNet/sing-box 到仓库同级目录（../../sing-box）。
replace github.com/sagernet/sing-box => ../../sing-box
