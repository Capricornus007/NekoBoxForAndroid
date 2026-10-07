#!/usr/bin/env bash
# 輸入端 sidecar 的 ELF 收口：app/executableSo/<abi>/<lib>.so 必須真的是那個 ABI 的 ELF。
#
# 為什麼要驗位元組而不是驗「檔在不在」：這批 .so 由 CI 的 sidecars job 產出，之後每個 job
# 都用 actions/cache 還原同一棵目錄樹。快取只要存过一次內容不对的檔（GOARCH 編錯、檔名放錯
# 目錄），existence 檢查會一路綠到打包，而 Gradle 對不認識的 .so 只是不塞進包裡，紅燈要等到
# 驗產物那層才亮——中間隔了一整個建置。所以這裡在吃進 Gradle 之前就把「這支檔確實是這個
# ABI」講死。
#
# 用法：check-sidecar-elf.sh <sidecar 根目錄>
#       掃根目錄下每顆 ABI 子目錄裡的所有 .so。
set -euo pipefail

root=${1:-}
if [ -z "$root" ] || [ ! -d "$root" ]; then
  echo "::error::check-sidecar-elf.sh 需要一個存在的 sidecar 根目錄（第 1 參數）" >&2
  exit 2
fi

# e_machine（little-endian，低位元組在前）與 ELFCLASS：Android ABI 名稱對 ELF 欄位。
want_class_of() {
  case $1 in
    armeabi-v7a | x86) printf '01\n' ;;
    arm64-v8a | x86_64) printf '02\n' ;;
    *) printf '?\n' ;;
  esac
}
want_machine_of() {
  case $1 in
    armeabi-v7a) printf '2800\n' ;;
    arm64-v8a) printf 'b700\n' ;;
    x86) printf '0300\n' ;;
    x86_64) printf '3e00\n' ;;
    *) printf '?\n' ;;
  esac
}

# 報錯時把 hex 換成人看得懂的欄位名（平民大白話版：看到 EM_386 就知道是 32 位元 x86）。
label_of() {
  case $1 in
    01) printf 'ELFCLASS32' ;;
    02) printf 'ELFCLASS64' ;;
    2800) printf 'EM_ARM' ;;
    b700) printf 'EM_AARCH64' ;;
    0300) printf 'EM_386' ;;
    3e00) printf 'EM_X86_64' ;;
    *) printf '0x%s' "$1" ;;
  esac
}

fail=0
checked=0
for abidir in "$root"/*/; do
  [ -d "$abidir" ] || continue
  abi=$(basename "$abidir")
  want_class=$(want_class_of "$abi")
  want_machine=$(want_machine_of "$abi")
  if [ "$want_class" = "?" ]; then
    echo "::error::$root 下有不是 ABI 的子目錄「$abi」——jniLibs 會把它整顆當成一個 ABI 收進去" >&2
    fail=1
    continue
  fi
  for f in "$abidir"*.so; do
    [ -e "$f" ] || continue
    checked=$((checked + 1))
    # e_ident[0..3]=magic、[4]=EI_CLASS、[5]=EI_DATA；e_type 在 16、e_machine 在 18（各 2 bytes）。
    set -- $(od -An -tx1 -N20 "$f" | tr -s ' \n' ' ')
    if [ "$#" -lt 20 ]; then
      echo "::error::$f 讀不到 20 bytes 的 ELF 標頭（只有 $(stat -c%s "$f" 2>/dev/null || echo '?') bytes）" >&2
      fail=1
      continue
    fi
    magic="$1$2$3$4"
    class=$5
    data=$6
    # 20 個 byte 裡：$1..$16 是 e_ident，$17/$18 是 e_type，$19/$20 才是 e_machine。
    machine="${19}${20}"
    if [ "$magic" != "7f454c46" ]; then
      echo "::error::$f 不是 ELF：前 4 bytes = $magic，期望 7f454c46" >&2
      fail=1
      continue
    fi
    if [ "$data" != "01" ]; then
      echo "::error::$f 不是 little-endian：ei_data = $data" >&2
      fail=1
      continue
    fi
    if [ "$class" != "$want_class" ] || [ "$machine" != "$want_machine" ]; then
      echo "::error::$f 跟目錄 $abi 對不上：實測 $(label_of "$class")/$(label_of "$machine")、期望 $(label_of "$want_class")/$(label_of "$want_machine")。Gradle 不會把這種檔塞進包裡，別等產物才紅。" >&2
      fail=1
      continue
    fi
  done
done

if [ "$checked" -eq 0 ]; then
  echo "::error::$root 底下一顆 .so 都沒掃到，不能當成「已備齊」放過" >&2
  exit 1
fi
if [ "$fail" -ne 0 ]; then
  echo "::error::sidecar ELF 收口未通過（掃了 $checked 支）" >&2
  exit 1
fi
echo "sidecar ELF 收口通過：掃了 $checked 支，每支的 ABI 都跟目錄對得上"
