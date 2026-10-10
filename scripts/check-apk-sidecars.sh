#!/usr/bin/env bash
# 公開發布 APK 的 sidecar 收口（「憑什麼要讓它缺席」：驗產物、不驗配置）。
#
# 為什麼要有這支腳本：SIDECARS 列出的每支 .so 都是 app/executableSo 下的原生子行程，由 CI 的
# sidecars job 產出、再由 jniLibs 目錄塞進包裡。這條鏈任何一段漏掉（artifact 沒下載、
# 快取指到半截目錄、某個 ABI 編譯失敗），Gradle 一樣綠、包照發，只有使用者用到對應功能時才發現不能用。所以收口落在下載得到的實物上：包裡有幾個 ABI，
# 每個 ABI 就都要有 SIDECARS 裡的每一支。
#
# 用法：check-apk-sidecars.sh <apk> [<apk> ...]
set -euo pipefail

# 每顆都要在包裡的 sidecar。新增第三條路的 core（libsingbox.so）跟 Proton 的取節點
# 子行程同類：缺了對應功能就完全不能用，所以一律「缺即紅」，不設計降級路徑。
SIDECARS=(libprotonvpn.so libsingbox.so)

if [ "$#" -eq 0 ]; then
  echo "::error::check-apk-sidecars.sh 沒有拿到任何 APK 路徑" >&2
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "::error::找不到 unzip —— 收口不能因為「工具不在」就放過" >&2
  exit 1
fi

fail=0
for apk in "$@"; do
  [ -f "$apk" ] || { echo "::error::$apk 不存在"; fail=1; continue; }
  echo "===== $apk"

  listing=$(unzip -Z1 "$apk" 2>&1) || {
    printf '%s\n' "$listing"
    echo "::error::$apk 讀不了 zip 目錄表（不是合法的 APK/ZIP）" >&2
    fail=1
    continue
  }

  # ABI 集合從包內實際的 lib/<abi>/*.so 路徑推，不寫死四支：per-ABI 拆包與
  # universal 包都適用，也才抓得住「只編好三支 ABI」這種情況。
  abis=$(printf '%s\n' "$listing" | sed -nE 's|^lib/([^/]+)/[^/]+\.so$|\1|p' | sort -u)
  if [ -z "$abis" ]; then
    echo "::error::${apk} 裡沒有任何 lib/<abi>/ 原生函式庫，這顆包根本沒帶 native 層，不能發布" >&2
    fail=1
    continue
  fi

  for sidecar in "${SIDECARS[@]}"; do
    case $sidecar in
      libprotonvpn.so) reason="Proton 取節點頁完全不能用" ;;
      libsingbox.so) reason="eBPF 第三條路完全起不來（root core 不在包裡）" ;;
      *) reason="對應功能完全不能用" ;;
    esac
    missing=""
    for abi in $abis; do
      # 用 case 逐字比對，不走 pipe：grep -qx 一命中就收工，printf 會噴
      # 「printf: write error: Broken pipe」把錯誤資訊混在日誌裡。
      case $'\n'"$listing"$'\n' in
        *$'\n'"lib/$abi/$sidecar"$'\n'*) ;;
        *) missing="$missing $abi" ;;
      esac
    done
    if [ -n "$missing" ]; then
      echo "::error::${apk} 缺 ${sidecar}：${missing# }（這些 ABI 上${reason}）" >&2
      echo "  包內的 native 清單（照這個對照 app/executableSo 與 jniLibs 到底哪一段掉了）：" >&2
      while IFS= read -r entry; do
        case $entry in lib/*) printf '    %s\n' "$entry" >&2 ;; esac
      done <<< "$listing"
      fail=1
    else
      echo "  ✓ ${sidecar} 齊備（$(printf '%s' "$abis" | tr '\n' ' ')）"
    fi
  done
done

if [ "$fail" -ne 0 ]; then
  echo "::error::sidecar 收口未通過" >&2
  exit 1
fi
echo "sidecar 收口通過：$# 個 APK 的每個 ABI 都帶了 ${SIDECARS[*]}"
