#!/bin/bash
# 把 sing-box 交叉編成四顆「可執行檔」放進包裡（app/executableSo/<abi>/libsingbox.so），
# 讓 app 能用 `su -c <nativeLibraryDir>/libsingbox.so run -c ...` 以 root 起一份獨立的核心。
#
# 為什麼要一顆獨立可執行檔、而不是直接用包裡的 libcore：libcore 是 gomobile 綁定，只能被 JVM
# 載入進 app 自己的行程；eBPF 那條路（第三條路）要在宿主機 root 下跑 sing-box 自己的資料路徑，
# 行程身分跟 app 不同，綁在 libcore 裡就永遠拿不到那個身分。取用方式不新發明：
# PluginManager.initNativeInternal() 已經用 soIfExist("libXXX.so") 從
# applicationInfo.nativeLibraryDir 取包內的可執行檔，BoxInstance 也已經有
# `commands.addAll(0, listOf("su","-c"))` 這條 root 執行路 —— 這支腳本只是把第四顆輪子
# 放到同一條既有的路上。
#
# 為什麼不走第二份 gomobile AAR（沿用 protonvpn.sh 檔頭的結論）：一個 app 只能有一份 gomobile
# 綁定，每份都自帶 go.Seq + libgojni.so，第二份會跟 libcore 撞。
#
# 源碼一律復用 ../sing-box，而且是 libcore 那同一個 COMMIT_SING_BOX：另外克隆一棵樹除了多抓
# GB 級歷史沒有別的好處（用戶的網路每天凌晨會斷，GB 級克隆失敗是常態），況且兩棵樹遲早漂移，
# 最後變成「APK 裡的 core 跟 sidecar 不是同一版 sing-box」那種查不到根因的坑。../sing-box 已存在
# 時用 git worktree 取 pin 的內容 —— 這樣不動那棵樹的 HEAD、也不碰它的工作區（它可能是別的代理
# 正在用的 checkout，例如 feat/ebpf-inbound）；不存在時（CI 乾淨機）只 --depth 1 取那一個 commit，
# 不做整條歷史的克隆。
#
# build tag 從 libcore/build.sh 讀、不自己抄一份：sidecar 少帶 core 那串裡的某個 with_xxx，
# 表現是「eBPF 模式連得上，但某類 outbound 不見了」，事後從日誌完全看不出是 tag 的問題。
#
# 版本字串讀 nb4a.properties 的 SINGBOX_VERSION（與 libcore/build.sh 同源）：sing-box 的
# constant.Version 預本是 "unknown"，靠 -X 在連結期注入。兩邊不同源的話 sidecar 自己報的版會跟
# About 頁顯示的差一版，實機排查時會把人帶往錯的方向。
#
# 需要 PATH 上有 Go 1.27+（sing-box 的 go.mod 直接寫 go 1.27.1）與 Android NDK。
#
# 用法：./run lib singbox-sidecar
set -e
set -o pipefail

source "buildScript/init/env.sh"
# COMMIT_SING_BOX 的單一來源 —— 跟 ./run lib core 讀同一個 pin，不在此處重複定義。
source "buildScript/lib/core/get_source_env.sh"

if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "Error: ANDROID_NDK_HOME is not set (NDK required to cross-compile the sing-box sidecar)." >&2
  exit 1
fi

NEKO_SINGBOX_REPO="${SINGBOX_REPO:-https://github.com/Capricornus007/sing-box.git}"
# 沒有 pin 就不知道在編什麼：任由它跟著分支尖端漂移，等於每次建置產出不同版的 sidecar，
# 而快取 key 又看不出內容變過 —— 寧可現在就紅。
if [ -z "${COMMIT_SING_BOX:-}" ]; then
  echo "Error: COMMIT_SING_BOX is empty (buildScript/lib/core/get_source_env.sh must pin it)." >&2
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "Error: go not found on PATH (the sing-box sidecar needs Go 1.27+)." >&2
  exit 1
fi
GO_VER="$(go env GOVERSION 2>/dev/null | sed 's/^go//')"
GO_MAJOR="${GO_VER%%.*}"
GO_REST="${GO_VER#*.}"; GO_MINOR="${GO_REST%%.*}"
if [ "${GO_MAJOR:-0}" -lt 1 ] || { [ "${GO_MAJOR:-0}" -eq 1 ] && [ "${GO_MINOR:-0}" -lt 27 ]; }; then
  echo "Error: Go $GO_VER is too old; the sing-box sidecar needs Go 1.27+." >&2
  exit 1
fi

DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64/bin"
fi
if [ ! -d "$DEPS" ]; then
  echo "Error: NDK LLVM toolchain not found under $ANDROID_NDK_HOME." >&2
  exit 1
fi

# 與 core 同一組 tag（缺了就會少了對應的 outbound／功能），再補上這顆 sidecar 的存在理由。
CORE_TAGS="$(sed -nE "s/.*-tags='([^']+)'.*/\1/p" libcore/build.sh | head -n1)"
if [ -z "$CORE_TAGS" ]; then
  echo "Error: 抓不到 libcore/build.sh 的 -tags='...'，不敢憑記憶拼 tag。" >&2
  exit 1
fi
# 已經含 with_ebpf 就不要再拼一次：CORE_TAGS 讀的是 libcore/build.sh，那邊加上 tag 之後，
# 這裡無條件追加會變成 `...,with_ebpf,with_ebpf`。Go 不會報錯，但日誌裡的 tag 字串會跟
# core 那串長得不一樣，之後比對兩邊 tag 差異時會誤判成「sidecar 多帶了東西」。
case ",$CORE_TAGS," in
  *,with_ebpf,*) TAGS="$CORE_TAGS" ;;
  *) TAGS="${CORE_TAGS},with_ebpf" ;;
esac

SINGBOX_VERSION="$(grep '^SINGBOX_VERSION=' nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')"
if [ -z "$SINGBOX_VERSION" ]; then
  echo "Error: nb4a.properties 裡沒有 SINGBOX_VERSION（libcore/build.sh 也依賴它，不同源就別編）。" >&2
  exit 1
fi

ROOT="$(pwd)"
REPO_DIR="$(cd .. && pwd)/sing-box"
TREE="$ROOT/.singbox-sidecar-build"
OUT="$ROOT/app/executableSo"
USED_WORKTREE=0

cleanup_tree() {
  # worktree 一定要用 git worktree remove 收，直接 rm 會在 ../sing-box/.git/worktrees 底下
  # 留一條指不到物件的註冊，之後別人 `git worktree list`／ prune 都會看到垃圾。
  if [ "$USED_WORKTREE" -eq 1 ]; then
    git -C "$REPO_DIR" worktree remove --force "$TREE" >/dev/null 2>&1 || rm -rf "$TREE"
  else
    rm -rf "$TREE"
  fi
}
trap cleanup_tree EXIT

echo ">> sing-box sidecar: repo=$NEKO_SINGBOX_REPO commit=$COMMIT_SING_BOX version=$SINGBOX_VERSION"
echo ">> build tags: $TAGS"

prepare_worktree() {
  # 只補抓缺的 commit，用明確 URL 而不是 remote 名稱：不去改別人那棵樹的 remote 設定。
  if ! git -C "$REPO_DIR" cat-file -e "${COMMIT_SING_BOX}^{commit}" 2>/dev/null; then
    echo ">> ../sing-box 裡沒有 $COMMIT_SING_BOX，補抓（不動它的工作區）"
    git -C "$REPO_DIR" fetch --quiet "$NEKO_SINGBOX_REPO" "$COMMIT_SING_BOX" ||
      git -C "$REPO_DIR" fetch --quiet origin "$COMMIT_SING_BOX" || return 1
  fi
  # 上次中斷留下的 worktree 可能是上一個 commit 的內容，拿它來編就會得到「腳本改了、產物卻
  # 是舊版」這種比沒產物更難查的狀態，所以一律移除重建。
  if [ -e "$TREE/.git" ]; then
    git -C "$REPO_DIR" worktree remove --force "$TREE" >/dev/null 2>&1 || rm -rf "$TREE"
  fi
  git -C "$REPO_DIR" worktree add --detach "$TREE" "$COMMIT_SING_BOX" >/dev/null || return 1
  USED_WORKTREE=1
}

prepare_shallow_clone() {
  # CI 乾淨機沒有 ../sing-box：只取那一顆 commit（--depth 1），不要把整條歷史拖下來。
  rm -rf "$TREE"
  mkdir -p "$TREE"
  git init -q "$TREE"
  git -C "$TREE" remote add origin "$NEKO_SINGBOX_REPO"
  git -C "$TREE" fetch --depth 1 origin "$COMMIT_SING_BOX" || return 1
  git -C "$TREE" checkout -q FETCH_HEAD || return 1
}

if [ -d "$REPO_DIR/.git" ]; then
  if ! prepare_worktree; then
    echo ">> 用 ../sing-box 開 worktree 失敗，退回獨立淺層克隆（只取 pin 那一顆 commit）" >&2
    USED_WORKTREE=0
    prepare_shallow_clone
  fi
else
  echo ">> 沒有 ../sing-box（CI 乾淨機）：淺層克隆 sing-box@${COMMIT_SING_BOX}"
  prepare_shallow_clone
fi

# eBPF 證據檢查的第一步：來源裡到底有沒有 eBPF 實作。這要在編之前、worktree 還在的時候問，
# 因為收尾會把 TREE 收掉。
EBPF_IMPL="protocol/ebpf/inbound.go"
if [ -f "$TREE/$EBPF_IMPL" ]; then
  SRC_HAS_EBPF=1
else
  SRC_HAS_EBPF=0
fi

build_abi() {
  local abi="$1" goarch="$2" cc="$3" goarm="${4:-}" pagesize="${5:-}"
  # -checklinkname=0 跟 sing-box 自己的 release/LDFLAGS 與 libcore/build.sh 一致： tailscale、
  # wireguard 那批依賴用了 go:linkname，Go 1.23 起預設會擋，少了這個字串就連結失敗。
  # -buildvcs=false：這棵樹是 worktree／淺層克隆，讓 go 去查 VCS 狀態只會因為「安全目錄」
  # 之類的環境問題把整場建置拖紅，而版本身分本來就由 -X constant.Version 負責。
  local ldflags="-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION"
  # Android 15+ 可能跑在 16 KB page 的裝置上，載入器會拒絕段對齊低於 page size 的 ELF；
  # 64 位元必須明確要求 16 KB，32 位元沒這要求、只會多付填充（同 protonvpn.sh）。
  if [ -n "$pagesize" ]; then
    ldflags="$ldflags -extldflags=-Wl,-z,max-page-size=$pagesize"
  fi
  echo ">> building libsingbox.so for $abi"
  mkdir -p "$OUT/$abi"
  ( cd "$TREE" && env GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CGO_ENABLED=1 \
    CC="$DEPS/$cc" \
    go build -trimpath -buildvcs=false -tags="$TAGS" -ldflags="$ldflags" \
      -o "$OUT/$abi/libsingbox.so" ./cmd/sing-box )
}

build_abi "arm64-v8a"   "arm64" "aarch64-linux-android21-clang" ""  16384
build_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi21-clang" "7"
build_abi "x86"         "386"   "i686-linux-android21-clang"
build_abi "x86_64"      "amd64" "x86_64-linux-android21-clang" ""  16384

# eBPF 證據檢查的第二步（產物層面）。
#
# 為什麼要有這一道：`with_ebpf` 打在「根本還沒有 eBPF 來源」的核心上不會報任何錯 —— Go 對
# 不認識的 build tag 是靜默忽略的，產照樣編出來、CI 照樣綠，使用者按了 eBPF 那個開關卻毫無
# 反應。這顆 sidecar 就是為 eBPF 而存在的，所以「tag 有沒有真的生效」必須被寫在日誌裡，
# 而不是留給日後猜。
#
# 為什麼找 github.com/cilium/ebpf 這串字串：protocol/ebpf 這個包在 tag 關閉時仍然存在
# （inbound_stub.go 照樣宣告 package ebpf），所以「包路徑有沒有出現在符號表」分不出真假；
# 只有真實作會經 common/ebpf/internal/bpfgen 連結進 cilium/ebpf。字串來自 pclntab，
# -s -w 不會把它們剝掉。
#
# 來源缺 eBPF 現在是硬紅：COMMIT_SING_BOX 已經抬到含 protocol/ebpf 的那筆（mod.35），
# 所以「來源裡找不到 inbound.go」只可能是 pin 被退回去、或 clone 到錯的倉。讓它紅，不要
# 印一段「這是預期狀態」把退版洗成綠燈 —— 這顆 sidecar 存在的理由就是 eBPF。
EBPF_MARKER="github.com/cilium/ebpf"
if [ "$SRC_HAS_EBPF" -eq 0 ]; then
  echo "Error: sing-box@$COMMIT_SING_BOX 的來源裡沒有 $EBPF_IMPL —— pin 被退掉了，" >&2
  echo "       這樣編出來的 sidecar 裡 with_ebpf 會被 Go 靜默忽略，等於沒有 eBPF。" >&2
  exit 1
fi
ebpf_missing=""
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  hits=$(grep -ac "$EBPF_MARKER" "$OUT/$abi/libsingbox.so" || true)
  if [ "${hits:-0}" -gt 0 ]; then
    echo ">>   $abi: 找得到 $EBPF_MARKER（$hits 處）→ eBPF 確實編進去了"
  else
    ebpf_missing="$ebpf_missing $abi"
  fi
done
if [ -n "$ebpf_missing" ]; then
  echo "Error: 來源有 eBPF 實作，但產物${ebpf_missing# } 找不到 $EBPF_MARKER —— with_ebpf 沒生效" >&2
  echo "       （tag 打錯、或 libcore/build.sh 那串被改動後拼錯）。這顆 sidecar 沒有 eBPF 就不能算完成。" >&2
  exit 1
fi
echo ">> eBPF 證據檢查通過：四支 ABI 的產物都帶著 $EBPF_MARKER"

# 缺檔／低對齊都在这里擋，不要等到包發出去才由使用者發現：16 KB page 的裝置會直接拒絕
# LOAD 對齊低於 16 KB 的 ELF（同 protonvpn.sh 與 scripts/verify-elf-alignment.sh 守的不变量）。
if command -v readelf >/dev/null 2>&1; then
  for abi in arm64-v8a x86_64; do
    min=""
    for alignment in $(readelf -lW "$OUT/$abi/libsingbox.so" | awk '/LOAD/{print $NF}'); do
      decimal=$(printf '%d' "$alignment")
      if [ -z "$min" ] || [ "$decimal" -lt "$min" ]; then
        min=$decimal
      fi
    done
    if [ "${min:-0}" -lt 16384 ]; then
      echo "Error: $abi libsingbox.so is NOT 16 KB aligned (min LOAD align ${min:-none})." >&2
      exit 1
    fi
    echo ">> $abi libsingbox.so min LOAD align = $min"
  done
else
  echo ">> readelf unavailable; skipped the 16 KB alignment check"
fi

echo ">> installed sing-box sidecar binaries:"
ls -la "$OUT"/*/libsingbox.so
# 這顆是整個 sing-box（含 tailscale）靜態鏈進一個檔，體積比其他 sidecar 大一個量級；
# 直接把數字印進日誌，要不要讓它進公開發布包這件事才有依據可判。
du -h "$OUT"/*/libsingbox.so
