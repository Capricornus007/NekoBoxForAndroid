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
