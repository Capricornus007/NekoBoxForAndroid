#!/usr/bin/env bash
# 公開發布 APK 的簽名收口（規則 71：驗產物、不驗配置）。
#
# 為什麼要有這支腳本：moregramX 的 signingConfigs 被一筆「把 build.gradle.kts 從上游
# 還原」的提交蓋掉之後，約一個月所有正式版包**完全未簽名**，而 CI 照綠、發布說明還寫著
# 「使用自訂簽名」。nb4a 這裡另有一個同款隱患：release.yml 是
#   if [ -n "${secrets.KEYSTORE_B64}" ]; then ... > release.keystore; fi
# ——secret 被改名或清掉時這句**靜默跳過**，gradle 就退回 debug 金鑰簽，badge 一樣全綠。
# 所以收口只認下載得到的實物：apksigner 自己說有沒有 v1/v2/v3、簽的是哪張憑證。
#
# 用法：check-apk-signature.sh <apk> [<apk> ...]
#   環境變數 REQUIRE_RELEASE_KEY=1 時，額外拒絕 CN=Android Debug（給正式版鏈用；
#   debug 通道不要設，它本來就是 debug 簽）。
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "::error::check-apk-signature.sh 沒有拿到任何 APK 路徑" >&2
  exit 1
fi

apksigner=""
for cand in \
  "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "/usr/local/lib/android/sdk"
do
  [ -n "$cand" ] && [ -d "$cand/build-tools" ] || continue
  found=$(find "$cand/build-tools" -type f -name apksigner 2>/dev/null | sort | tail -1)
  [ -n "$found" ] && { apksigner="$found"; break; }
done
if [ -z "$apksigner" ]; then
  echo "::error::找不到 apksigner（ANDROID_HOME=$ANDROID_HOME）——收口不能因為「工具不在」就放過" >&2
  exit 1
fi
echo "apksigner = $apksigner"

fail=0
for apk in "$@"; do
  [ -f "$apk" ] || { echo "::error::$apk 不存在"; fail=1; continue; }
  echo "===== $apk"
  # --min-sdk-version 21 不可省：包的 minSdk>=24 時 apksigner 會跳過 v1 檢查直接回
  # v1: false，那會把有簽的好包誤判成壞包（本倉踩過兩次）。
  out="$("$apksigner" verify --verbose --min-sdk-version 21 --print-certs "$apk" 2>&1)" || {
    echo "$out"
    echo "::error::$apk apksigner 判定失敗（很可能完全沒簽）" >&2
    fail=1
    continue
  }
  printf '%s\n' "$out"
  for scheme in v1 v2 v3; do
    if printf '%s\n' "$out" | grep -qE "^Verified using ${scheme} scheme .*: *true"; then
      echo "  ✓ ${scheme} 已簽"
    else
      echo "  ::error::${apk} 缺 ${scheme} 簽名（自家公開發布包一律要 v1+v2+v3）"
      fail=1
    fi
  done
  # 欄位名前綴會隨 build-tools 版本變：舊版是「Signer #1 certificate DN:」、
  # 實測 build-tools 37.0.0 是「V3.0 Signer: certificate DN:」→ 只錨在後面那段，
  # 不然會像第一次那樣靜默抓不到憑證（抓不到不等於沒簽，別拿它當放行或判死依據）。
  dn=$(printf '%s\n' "$out" | sed -nE 's/.*certificate DN: (.*)$/\1/p' | head -1)
  sha=$(printf '%s\n' "$out" | sed -nE 's/.*certificate SHA-256 digest: (.*)$/\1/p' | head -1)
  echo "  憑證 DN=${dn:-（查不到）}"
  echo "  憑證 SHA-256=${sha:-（查不到）}"
  if [ "${REQUIRE_RELEASE_KEY:-0}" = 1 ] && printf '%s' "$dn" | grep -qi 'Android Debug'; then
    echo "  ::error::${apk} 是 debug 金鑰簽的，正式版鏈不能發這種包（跨版憑證會變、用戶只能先卸再裝）"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "::error::簽名收口未通過" >&2
  exit 1
fi
echo "簽名收口通過：$# 個 APK 都有 v1+v2+v3"
