#!/usr/bin/env bash
# 核心 pin 一致性閘門。
#
# 為什麼需要這支腳本（兩次實戰教訓）：
# nb4a 的核心不是由「一個 commit」決定的。`buildScript/lib/core/get_source_env.sh` 裡
# **每一條 `COMMIT_*` 都是 pin**（sing-box／libneko／wireguard-go／sing-quic／juicity／trusttunnel），
# 而 sing-box 自己的 `go.mod` 也用 `replace` 釘著同一批 fork。兩邊一旦不同步：
#   - 本機**看不出來**——`libcore/go.mod` 有 `replace github.com/sagernet/sing-box => ../../sing-box`
#     這類檔案系統替換，本地 build 照樣綠；
#   - 只有 CI（照 pin 在乾淨環境 clone）會紅。
# 2026-09-27 就是 `COMMIT_WIREGUARD_GO` 留在舊 commit 讓三顆 job 全紅；2026-09-28 又實測到
# `COMMIT_SING_QUIC` 與 sing-box 的 pin 不同步（那回兩邊只差 workflow 檔所以沒炸——
# 「這次沒炸」不是理由）。而 `update-sing-box.yml` 那個 bot 只自動抬 `COMMIT_SING_BOX`，
# 另外五條永遠要人對。
#
# 兩段檢查、門檻不同是故意的：
#   段 1（硬失敗）：nb4a 的 pin 必須等於 sing-box `go.mod` 對同一模組的 replace 版本。
#                  這是「會炸建置」的那種不一致。
#   段 2（只報告）：每條 pin 是否等於該 fork 預設分支尖端。別人推了新 commit 不該讓我們的 PR 變紅，
#                  但要把落後講出來，否則沒人知道該抬。
set -euo pipefail

ENV_FILE="buildScript/lib/core/get_source_env.sh"
OWNER="Capricornus007"
# 段 1 要比的模組：nb4a 的 COMMIT_ 鍵名 -> sing-box go.mod 裡的 fork 倉名
HARD_PAIRS="WIREGUARD_GO:wireguard-go SING_QUIC:sing-quic SING:sing"

[ -f "$ENV_FILE" ] || { echo "FAIL: 找不到 $ENV_FILE（請在倉庫根目錄跑）"; exit 2; }

read_pin() { grep -oP "^export COMMIT_$1=\"\K[0-9a-f]{7,40}" "$ENV_FILE" || true; }

failures=0
behind=0

echo "== 段 1：nb4a 的 pin vs sing-box go.mod 的 replace（硬比較）=="
sb_pin=$(read_pin SING_BOX)
[ -n "$sb_pin" ] || { echo "FAIL: 讀不到 COMMIT_SING_BOX"; exit 2; }
gomod=$(mktemp)
trap 'command rm -f "$gomod"' EXIT
fetched=0
for attempt in 1 2 3; do
    if curl -fsSL --max-time 60 "https://raw.githubusercontent.com/$OWNER/sing-box/$sb_pin/go.mod" -o "$gomod"; then
        fetched=1
        break
    fi
    echo "  第 $attempt 次抓 sing-box go.mod 失敗，重試…"
    sleep 3
done
if [ "$fetched" -ne 1 ]; then
    echo "FAIL: 抓不到 sing-box@$sb_pin 的 go.mod（三次都失敗：網路問題或該 commit 不存在）"
    exit 1
fi
for pair in $HARD_PAIRS; do
    key=${pair%%:*}; repo=${pair##*:}
    nb=$(read_pin "$key")
    [ -n "$nb" ] || { echo "  跳過 $repo：nb4a 沒有 COMMIT_$key"; continue; }
    sb=$(grep -oP "=> github\.com/$OWNER/${repo//./\\.} v[^ ]*-\K[0-9a-f]{12}" "$gomod" | head -1 || true)
    [ -n "$sb" ] || { echo "  跳過 $repo：sing-box go.mod 沒用 replace 釘它"; continue; }
    if [ "${nb:0:12}" = "$sb" ]; then
        echo "  一致      $repo  nb4a=${nb:0:12} sing-box=$sb"
    else
        echo "  **不同步**  $repo  nb4a=${nb:0:12} sing-box=$sb"
        echo "            → 本機因 ../../ 檔案系統 replace 不會紅，只有 CI 會紅；把兩邊抬到同一個 commit。"
        failures=$((failures + 1))
    fi
done

echo "== 段 1b：libcore/go.mod 的模組 replace vs sing-box go.mod（硬比較）=="
# 為什麼要比這一段：libcore 是**獨立主模組**，Go 只有主模組的 replace 生效，所以 libcore 一旦
# 自己釘了某個模組，它就**蓋掉** sing-box go.mod 裡的要求。結果是「APK 裡跑的」跟
# 「sing-box 自己 CI 測過的」是兩份代碼，而本機因 `replace … => ../../sing-box` 永遠綠。
# 2026-09-28 實測到這正是既存狀態：sing-box 釘 sing-tun a3c6c0d、libcore 釘 5e3cd25（差 25 筆），
# 而上面的段 1 完全看不見——因為 nb4a 根本沒有 COMMIT_SING_TUN 這種鍵。
LCORE="libcore/go.mod"
if [ ! -f "$LCORE" ]; then
    echo "  FAIL: 找不到 $LCORE（libcore 這側沒可比）"
    failures=$((failures + 1))
else
    while read -r repo; do
        [ -n "$repo" ] || continue
        esc=${repo//./\\.}
        # 比「完整版本字串」而不是只取結尾 12 位 hash：pseudo-version 與標籤式版本
        # （quic-go 的 v0.61.0-sing-box-mod.9、amneziawg-go 的 v3.1.20260828-mod.2）都要能比，
        # 否則守門對那兩顆是瞎的——它們恰恰是最容易一邊抬了一邊沒抬的模組。
        lc=$(grep -oP "=> github\.com/$OWNER/${esc} \K\S+" "$LCORE" | head -1 || true)
        sb=$(grep -oP "=> github\.com/$OWNER/${esc} \K\S+" "$gomod" | head -1 || true)
        if [ -z "$sb" ]; then
            echo "  僅 libcore 釘 $repo（sing-box go.mod 沒這個模組，無可比）"
            continue
        fi
        if [ "$lc" = "$sb" ]; then
            echo "  一致      $repo  $lc"
        else
            echo "  **不同步**  $repo  libcore=$lc sing-box=$sb"
            echo "            → APK 吃 libcore 那版、核心倉 CI 測的是另一版；把兩邊抬到同一個 commit。"
            failures=$((failures + 1))
        fi
    done < <(grep -oP "=> github\.com/$OWNER/\K[a-z0-9._/-]+(?= v)" "$LCORE" | sort -u)
fi

echo "== 段 1c：核心版本字串的兩處記錄必須一致（硬比較）=="
# 教訓（2026-10-05）：抬 sing-box pin 時只改 get_source_env.sh 的 VERSION_SING_BOX 是不夠的——
# 真正蓋進 libgojni.so 的是 libcore/build.sh:31 從 nb4a.properties 讀的 SINGBOX_VERSION
# （build.sh:38 用 -X github.com/sagernet/sing-box/constant.Version=… 注入）。
# 兩處不同步時，產物裡印出來的核心版本會停在舊值，看起來就像「CI 綠但裝上去還是舊核心」，
# 實測因此白查過一輪（APK 明明是新的、字串卻報 mod.23）。
V_ENV=$(grep -oP '^export VERSION_SING_BOX="\K[^"]+' "$ENV_FILE" 2>/dev/null | tr -d '\r' || true)
V_PROP=$(grep -oP '^SINGBOX_VERSION=\K[^ ]+' nb4a.properties 2>/dev/null | tr -d '\r' || true)
V_ENV=${V_ENV#v}
V_PROP=${V_PROP#v}
if [ -z "$V_ENV" ] || [ -z "$V_PROP" ]; then
    echo "  **資料缺失**  VERSION_SING_BOX='$V_ENV' SINGBOX_VERSION='$V_PROP'（任一為空就查不出漂移）"
    failures=$((failures + 1))
elif [ "$V_ENV" != "$V_PROP" ]; then
    echo "  **不同步**  get_source_env.sh=$V_ENV  nb4a.properties=$V_PROP"
    echo "            → app 顯示與二進位內嵌的是 nb4a.properties 那一個；兩邊要一起抬。"
    failures=$((failures + 1))
else
    echo "  一致      $V_ENV"
fi

echo "== 段 1e：sing-box 有 replace、libcore 也吃這個模組 → libcore 必須帶同一條 replace（硬比較）=="
# 段 1b 是「libcore 釘了、sing-box 沒跟上」；這一段補反方向：**sing-box 換了 fork、
# libcore 卻沒寫那條 replace**。因為 replace 只在主模組生效，libcore 會靜默去吃上游原版，
# 於是 APK 裡跑的跟 sing-box CI 測過的不是同一份代碼，而本機因 ../../ 檔案系統 replace 永遠綠。
# 2026-10-06 實測踩到兩條：① sing-box 的 amneziawg 換 v3 之後新拉進 gvisor.dev/gvisor，
# sing-box 把它 replace 成 Capricornus007/gvisor-awg，libcore 卻沒有那條；
# ② libcore/tailscale*.go 直接 import github.com/sagernet/tailscale（build.sh 也帶 ts_omit_*），
# 但 sing-box 早已把該模組換成 Capricornus007/tailscale、libcore 沒跟 → APK 裡的 tailscale
# 一直編上游原版。兩條都只能靠逐條比對抓得到。
if [ -f "$LCORE" ]; then
    while IFS='|' read -r amod atgt; do
        [ -n "$amod" ] || continue
        esc=${amod//./\\.}
        if ! grep -qE "^[[:space:]]+${esc} v" "$LCORE"; then
            continue   # libcore 的依賴圖根本沒這個模組，不用比
        fi
        lctgt=$(grep -oP "^[[:space:]]*replace[[:space:]]+${esc}[[:space:]]+=>[[:space:]]+\K.*" "$LCORE" | head -1 || true)
        # 檔案系統路徑（../../wireguard-go 那類）是隔離編譯副本的既有佈局，不是「沒換 fork」。
        # 路徑型 replace 沒有版本字串可逐字比對，只能比「有沒有這條」，所以直接算通過。
        case "$atgt" in
            .*|/*) continue ;;
        esac
        case "$lctgt" in
            .*|/*)
                echo "  本地路徑  $amod  libcore=$lctgt（隔離編譯佈局，sing-box=$atgt）"
                continue
                ;;
        esac
        if [ -z "$lctgt" ]; then
            echo "  **缺 replace**  libcore 吃 $amod 但沒換成 fork：sing-box 用 $atgt"
            echo "            → APK 會用上游原版而不是我方 fork；把同一條 replace 補進 libcore/go.mod。"
            failures=$((failures + 1))
        elif [ "$lctgt" != "$atgt" ]; then
            echo "  **不同步**  $amod  libcore=$lctgt sing-box=$atgt"
            failures=$((failures + 1))
        else
            echo "  一致      $amod  $lctgt"
        fi
    done < <(awk '/^replace[[:space:]]/{tgt=""; for(i=2;i<=NF;i++){if($i=="=>"){tgt=$i; continue} if(tgt!=""){tgt=tgt" "$i}}  sub(/^[[:space:]]*replace[[:space:]]+/,""); n=split($0,a," => "); if(n==2){gsub(/[[:space:]]+$/,"",a[1]); gsub(/^[[:space:]]+|[[:space:]]+$/,"",a[2]); if(a[2]!="") print a[1]"|"a[2]}}' "$gomod")
fi

echo "== 段 2：每條 COMMIT_* 是否等於該 fork 預設分支尖端（僅報告）=="
while read -r key; do
    [ -n "$key" ] || continue
    pin=$(read_pin "$key")
    repo=$(printf '%s' "$key" | tr 'A-Z_' 'a-z-')
    tip=$(git ls-remote --symref "https://github.com/$OWNER/$repo.git" HEAD 2>/dev/null | awk '!/^ref:/ && NF >= 2 {print $1; exit}')
    if [ -z "$tip" ]; then
        echo "  查不到    COMMIT_$key 對應的 $OWNER/$repo（倉名可能不是直接轉換得來，跳過）"
        continue
    fi
    if [ "$pin" = "$tip" ]; then
        echo "  在尖端    COMMIT_$key=${pin:0:12}  ($repo)"
    else
        echo "  落後      COMMIT_$key=${pin:0:12} 尖端=${tip:0:12}  ($repo) ← 該抬了"
        behind=$((behind + 1))
    fi
done < <(grep -oP '^export COMMIT_\K[A-Z_]+' "$ENV_FILE")

if [ "$failures" -gt 0 ]; then
    echo "FAIL: $failures 條核心 pin 與 sing-box 不同步"
    exit 1
fi
echo "OK: 可比對的核心 pin 全數一致；另有 $behind 條落後尖端（僅報告，不擋 PR）"
