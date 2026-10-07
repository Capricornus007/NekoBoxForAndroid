#!/usr/bin/env bash
# 守「每個會畫面的 Activity 都要走 ThemedActivity」這條線。
#
# 為什麼要有這支腳本：本 app 的配色不是靠預設 theme，而是 ThemedActivity.onCreate 裡
# 呼叫 Theme.apply(this) 才套進去的。誰直接繼承 AppCompatActivity，誰那頁的
# colorPrimary 就是錯的——實測後果不是「沒顏色」而是「字看不到」：TextButton／
# OutlinedButton 的標籤與對話框按鈕都取 colorPrimary，畫出來跟背景同色，用戶看到的就是
# 「登出、重新整理、我明白了、取消全部不見」（ProtonActivity 與 TrafficChartActivity
# 都踩過，Gradle 與 lint 全綠、截圖才露餡）。這種壞法編譯器抓不到，所以落在這裡。
#
# 用法：check-themed-activities.sh [app 原始碼目錄]
set -euo pipefail

src=${1:-app/src/main/java}
if [ ! -d "$src" ]; then
  echo "::error::找不到 $src（要在倉庫根目錄跑）" >&2
  exit 2
fi

# 不畫任何自己控件的純工具 Activity：它們沒有需要配色的文字，硬套主題反而會多一次
# recreate（BlankActivity 是透明中轉、VpnRequestActivity 只拉 VPN／解鎖面板）。
allowlist="BlankActivity VpnRequestActivity"

fail=0
found=0
while IFS= read -r f; do
  cls=$(basename "$f" .kt)
  case " $allowlist " in
    *" $cls "*) continue ;;
  esac
  # 只認「直接繼承 AppCompatActivity」這一種寫法；ThemedActivity 與各種 Fragment 基底不管。
  if sed -n "s/^class $cls[^:]*: *AppCompatActivity(.*/X/p" "$f" | grep -q X; then
    echo "::error::$f 直接繼承 AppCompatActivity —— 這頁沒走 Theme.apply，colorPrimary 取錯會把按鈕文字畫成看不見的顏色。要畫面就改繼承 ThemedActivity。" >&2
    fail=1
  else
    found=$((found + 1))
  fi
done < <(find "$src" -name "*Activity.kt" -type f | sort)

if [ "$fail" -ne 0 ]; then
  echo "::error::有 Activity 繞過 ThemedActivity（實測過會把按鈕字畫不見，見腳本註解）" >&2
  exit 1
fi
# shellcheck disable=SC2206  # 白名單就是空白分隔的詞
allowed=($allowlist)
echo "OK：會畫面的 Activity 全部走 ThemedActivity（查了 $found 支，另有 ${#allowed[@]} 個白名單工具頁）"
