# MAINTENANCE — updating & building ZYBC-VPN-Android

This project = **NekoBox for Android (sing-box VPN core)** + **byedpi (DPI bypass)**.
This guide is written to be followed **literally, top to bottom**, by anyone (including a
simple automated assistant). Run every command from the project root
(`.../SELF/ZYBC-VPN-Android`) unless told otherwise.

> Golden rule: **never hand-edit `libcore/go.mod`, `libcore/go.sum`, or rewrite import
> paths to force a version.** The VPN core version is controlled by TWO commit hashes in
> `buildScript/lib/core/get_source_env.sh`. You change versions there and let the build
> system regenerate everything. If you find yourself editing go.mod by hand, stop — you are
> off the rails.

---

## 0. How the project is wired (read once)

| Piece | Where it comes from | How it's pinned |
|-------|--------------------|-----------------|
| VPN core (sing-box + libneko) | `github.com/MatsuriDayo/sing-box`, `github.com/MatsuriDayo/libneko` | commit hashes in `buildScript/lib/core/get_source_env.sh` |
| Go glue (`libcore/`) | in this repo | committed source |
| byedpi (native C) | `github.com/hufrea/byedpi` via the **ByeByeDPI** repo's submodule | copied into `app/src/main/cpp/byedpi/` |
| Android app (Kotlin) | in this repo | committed source |

The core is built into `app/libs/libcore.aar` by `./run lib core` (which uses
`gomobile-matsuri bind` — NOT `go build`). Gradle then builds the APK and compiles the
byedpi C via CMake. The `../sing-box` and `../libneko` folders next to this repo are
**working clones the build checks out** to the pinned commits — they are not the source of
truth, the pin file is.

---

## 1. Prerequisites (one-time)

- **Go** ≥ 1.25, **JDK 21**, **git**, internet access.
- **Android SDK** containing: `platforms/android-35`, `build-tools/35.0.1`, `cmake/3.22.1`,
  and an **NDK** (25.x works). On this machine that SDK is
  `/home/aiuser/workspace/SOFTWARE/AndroidSDK`.
- Edit `scripts/env.sh` if your SDK path or NDK version differ.
- `local.properties` must point at the SDK (already set):
  ```
  sdk.dir=/home/aiuser/workspace/SOFTWARE/AndroidSDK
  ndk.dir=/home/aiuser/workspace/SOFTWARE/AndroidSDK/ndk/25.2.9519653
  ```

Every shell that builds must first run:
```bash
source scripts/env.sh
```

---

## 2. Build everything (no version change)

```bash
source scripts/env.sh
scripts/build-all.sh
```
That runs the two canonical steps and prints the APK path. Equivalent manual steps:
```bash
source scripts/env.sh
./run lib core                       # -> app/libs/libcore.aar   (first run also installs gomobile-matsuri; slow)
./gradlew app:assembleFdroidRelease  # -> app/build/outputs/apk/fdroid/release/*.apk
```

Outputs:
- Native core: `app/libs/libcore.aar`
- APK: `app/build/outputs/apk/fdroid/release/*.apk`
  (For a **signed** APK, export `KEYSTORE_PASS=...` before Gradle; it signs with
  `release.keystore`. Without it the fdroid APK is unsigned.)

---

## 3. Pulling upstream updates

### 3a. Update byedpi (safe, mechanical)
```bash
source scripts/env.sh
scripts/update-upstream.sh            # updates ByeByeDPI's byedpi submodule + copies C files in
git diff app/src/main/cpp/byedpi      # review
scripts/build-all.sh                  # rebuild
```
`scripts/sync-byedpi.sh` alone re-copies the C sources without touching git.

> Note: `app/src/main/cpp/byedpi/proxy.c` may show a small diff because this app carried a
> few extra debug `LOG(...)` lines. Taking upstream's version (dropping those logs) is fine.

### 3b. Bump the VPN core (sing-box / libneko) — MAY REQUIRE CODE CHANGES

1. See current pins and latest upstream commits:
   ```bash
   scripts/update-upstream.sh          # prints both; does NOT auto-bump the core
   ```
2. Edit `buildScript/lib/core/get_source_env.sh`, set `COMMIT_SING_BOX` (and/or
   `COMMIT_LIBNEKO`) to the new commit hash.
3. Rebuild the core:
   ```bash
   source scripts/env.sh
   ./run lib core
   ```
4. **If it builds → done**, continue to `./gradlew ...`. **If it fails to compile**, you hit
   an upstream API/layout change — that is a porting task, see next section. Do not paper
   over it by editing go.mod.

#### When the core bump breaks compilation
Known breaking change in MatsuriDayo/sing-box **after tag `1.12.19-neko-1`**:
- packages moved: `sing-box/protocol/*` → `sing-box/inbound/*` **and** `sing-box/outbound/*`
  (e.g. `protocol/vless` → `inbound/vless`; `protocol/block`,`protocol/wireguard` →
  `outbound/block`,`outbound/wireguard`). Fix `libcore/box_include.go` imports accordingly.
- `sing-box/nekoutils` was **removed**. `libcore/geoip.go`, `libcore/geosite.go`,
  `libcore/nb4a.go` call `nekoutils.GetGeoIPHeadlessRules` / `GetGeoSiteHeadlessRules`;
  find the current upstream equivalent (or restore the helpers) — do not just comment them
  out, that silently breaks geoip/geosite rule parsing.

The safe fallback if a bump is too costly right now: **revert the pin** in
`get_source_env.sh` back to `1.12.19-neko-1`
(`aed32ee3066cdbc7d471e3e0415c5134088962df`) and ship that; it is a known-good baseline.

---

## 4. Troubleshooting (symptom → fix)

| Symptom | Cause | Fix |
|---|---|---|
| `NDK not found` / `env_ndk.sh: Error` | `ANDROID_NDK_HOME` unset or bad NDK | `source scripts/env.sh`; set `NDK_VERSION` to an installed NDK |
| `gomobile-matsuri: command not found` | core init didn't finish | re-run `./run lib core`; ensure `$(go env GOPATH)/bin` on PATH (env.sh does this) |
| `package github.com/sagernet/sing-box/protocol/... is not in std` (or similar missing package) | pinned core commit doesn't match `libcore/` imports | you bumped the core — do the port in §3b, or revert the pin |
| `undefined: nekoutils.*` | bumped past the commit that removed nekoutils | see §3b |
| Gradle: `SDK location not found` | `local.properties` / `ANDROID_HOME` wrong | fix `local.properties` `sdk.dir`; `source scripts/env.sh` |
| Gradle: `Failed to find CMake 3.22.1` | cmake missing | install via sdkmanager: `sdkmanager 'cmake;3.22.1'` |
| Gradle: `overrideLibrary`/minSdk merge error for `androidx.work` | a dep raises minSdk | add `androidx.work` to `tools:overrideLibrary` in `app/src/main/AndroidManifest.xml` |
| APK is unsigned | no keystore password | `export KEYSTORE_PASS=...` before Gradle (signs with `release.keystore`) |

---

## 5. Helper scripts (in `scripts/`)

| Script | Does |
|---|---|
| `env.sh` | `source` it first — exports SDK/NDK/PATH |
| `build-all.sh` | core (`./run lib core`) + APK (`./gradlew`) in one go |
| `sync-byedpi.sh` | copy byedpi C sources from `../ByeByeDPI` into the app |
| `update-upstream.sh` | update byedpi automatically; print core pin bump info (no auto-bump) |
