//go:build tools

// 這顆檔案存在的唯一目的，是讓 `go mod tidy` 保留 golang.org/x/mobile 這條 require。
//
// 因果（完整版寫在 libcore/init.sh）：libcore/build.sh 在 `gomobile bind` 之前一定要
// 跑 tidy，而 #181 之後沒有任何 .go 檔 import x/mobile（asset 改吃
// github.com/sagernet/gomobile/asset），tidy 就把它整條剪掉。工具鏈那顆 gobind 是
// MatsuriDayo fork、模組路徑仍是 golang.org/x/mobile，啟動時要用 go/packages 定位
// 自己的 bind 套件 → 定位不到就在生成 binding 之前死：
//   unable to import bind: no Go package in golang.org/x/mobile/bind
//
// `go mod tidy` 無視 build tag 一律彙整 import，所以這行空白 import 只進模組圖、
// 不會進任何產物：`tools` 標籤不會被 gomobile bind、go build、CI 任何一路帶上。
package libcore

import _ "golang.org/x/mobile/bind"
