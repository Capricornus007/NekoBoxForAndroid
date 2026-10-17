# dev.13 verification and device regression

## Automated coverage

- JVM: control-URL validation, independent per-route group tags, DNS transports and bootstrap, subscription fingerprints and secret-free diff categories, exact SSID precedence and unknown-SSID fallback, correlation-ID event filters, per-app TUN guard generation.
- Native with race detection: DNS reply ID/question validation, UDP vs DNS status, TCP vs verified TLS, bounded diagnostics, fallback quorum/hysteresis, routing and full Android UID guards (including other-profile UIDs), encrypted WG/AWG 1–3.1 IPv4/IPv6 UDP/ICMP exchange. MTU probes run with an existing TUN return owner; unrelated ICMP still reaches that owner. TCP/UDP socket handling and owner checks are unchanged.
- SQLite: existing two-database restore transaction tests. Room schema 7→8 only adds two default-zero route columns.

Run local checks before the single release build:

```sh
./buildScript/lib/core/get_source.sh
./buildScript/lib/assets.sh
cd libcore
go test -race -tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api . github.com/sagernet/sing-box/protocol/group github.com/sagernet/sing-box/transport/wireguard
cd ..
python3 buildScript/tests/test_restore_transaction.py
./gradlew :app:testOssReleaseUnitTest
```

## ADB smoke runner

`buildScript/tests/android_regression.py` uses only Python's standard library and adb. Tap positions come from current UI bounds. It launches an already installed app, can open the first visible profile's diagnostics through its overflow menu, verifies the network-check input is present, takes screenshots, and checks process survival and the app's crash-buffer marker. A caught UI-construction exception must fail the form assertion even if the process stays alive. It **does not prove VPN traffic recovery or isolation**.

In dev.13.1, diagnostics is also available directly in the navigation drawer for the running profile (or the selected profile when stopped). It no longer lives inside Share → Configuration. Check both entry points, all diagnostic input/filter fields, and the network-policy editor: programmatically added `TextInputEditText` children must use `LinearLayout.LayoutParams`, as required by `TextInputLayout`.

Use a dedicated test device and preconfigure test profiles. The `--network-cycle` option changes Wi-Fi/mobile switches and restores both afterwards. The `--uid-fixture-apk` option requires a separate disposable APK; its package identity is checked with SDK aapt2, an already installed package is rejected, and the new package is removed afterwards. Screenshots may contain profile names and remain local; no artifacts are uploaded automatically.

```sh
adb devices -l
python3 buildScript/tests/android_regression.py --serial emulator-5554 --output /tmp/neko-qa --diagnostics
python3 buildScript/tests/android_regression.py --serial emulator-5554 --output /tmp/neko-network-qa --network-cycle --sleep-wake
python3 buildScript/tests/android_regression.py --serial emulator-5554 --output /tmp/neko-uid-qa --uid-fixture-apk /tmp/uid-fixture.apk --uid-fixture-package org.example.uidfixture --aapt2 /path/to/android-sdk/build-tools/35.0.1/aapt2
```

## Device matrix and manual assertions

Test Android 8/11/14/15+, RU/EN, light/dark/AMOLED/dynamic colors, large font/display size, portrait/landscape, TalkBack and a device with a managed work profile.

1. Start VPN, switch Wi-Fi→mobile→Wi-Fi and sleep/wake. Confirm traffic resumes, selected network profile/DNS match the active transport, live counters advance and no repeated restart loop occurs. Manual selection stays stable during mere link-address/DNS refreshes.
2. SSID: exact match beats generic Wi-Fi; another/unknown SSID uses generic Wi-Fi. On Android 10+, enable precise location "all the time" only for optional SSID matching. Deny location permission or disable system location: app keeps working using the generic policy. Turning automation off restores global DNS behavior. Full custom JSON keeps its own DNS.
3. Add/remove the disposable package while per-app VPN is active. Confirm the debounced full restart rebuilds package→UID mappings and forwarding resumes.
4. From an excluded app and its work-profile copy, inspect interfaces and attempt direct TCP, UDP, DNS and ICMP. The proxy exit must stay hidden; unknown/other-profile TUN flows must be rejected. Repeat with an allowed app to confirm its VPN traffic still works. Shell traffic alone is not an adequate work-profile isolation test.
5. For each direct-route mode, block/unblock a control site, exercise quorum/failure/recovery thresholds and watch per-site states. Local addresses and resolver bootstrap remain direct. Separate backup profile and its DNS must switch together. Custom rule JSON is allowed to override the generated route.
6. Diagnostics: history exists only at debug/trace, filters join only full correlation IDs, live refresh stops when dismissed/backgrounded, input and scrolling remain usable. Explicit probes compare direct/current proxy. UDP DNS is a check of the entered resolver, not proof of configured DoH/DoT. No ICMP reply means unknown; verified size is an inner packet lower bound, not outer UDP PMTU. MTU never changes automatically.
7. Subscription: cancel preview and verify DB unchanged; apply additions/renames/address/TLS/DNS/AllowedIPs/key changes and verify stable IDs where applicable. Undo restores old profiles/IDs and retains overlapping traffic counters. Local edits while confirmation is open abort the update/undo. Undo rejects deleting newly added referenced nodes. Unchanged automatic refresh must not overwrite the previous undo point. Check background update behavior and referenced-removal prompts.
8. Restore an existing dev.12 backup and migrate a schema-7 installation. Existing routes inherit the old global behavior. Check signed upgrade over the installed stable development certificate; release APK must not be debuggable.

## Material 3 Expressive scope

The app already uses Material Views 1.14 and the Expressive theme. New diagnostics/network screens use semantic surface/container roles, grouped cards, labelled controls and a prominent primary action; secondary configuration/edit actions are outlined. No Compose migration or decorative redesign is required.

Official references:
- https://m3.material.io/foundations/usability/applying-m-3-expressive
- https://design.google/library/expressive-material-design-google-research
- https://m3.material.io/develop/android/mdc-android
- https://developer.android.com/reference/android/net/wifi/WifiInfo
- https://developer.android.com/develop/sensors-and-location/location/permissions/background

No Android device/emulator was attached in the development session. Automated JVM/native checks are distinct from the physical-device assertions above; screenshots, TalkBack, SSID permission behavior, OEM sleep recovery and work-profile isolation still require that device pass.
