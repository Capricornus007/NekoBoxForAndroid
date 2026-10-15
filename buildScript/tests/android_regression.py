#!/usr/bin/env python3
"""ADB smoke/regression runner. Use a dedicated test device; outputs stay local."""
import argparse
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.isaiandco.neko")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--diagnostics", action="store_true", help="Open diagnostics for first visible profile via its share menu")
    parser.add_argument("--network-cycle", action="store_true", help="Cycle Wi-Fi/mobile data; restore original switches afterwards")
    parser.add_argument("--sleep-wake", action="store_true")
    parser.add_argument("--uid-fixture-apk", type=pathlib.Path, help="Install then remove a disposable fixture to trigger UID refresh")
    parser.add_argument("--uid-fixture-package")
    parser.add_argument("--aapt2", default="aapt2", help="Android SDK aapt2 executable for fixture identity validation")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    report = {"package": args.package, "checks": [], "limitations": ["Does not prove work-profile network isolation or real-world whitelist behavior"]}
    start = time.strftime("%m-%d %H:%M:%S.000", time.localtime())

    def adb(*command, binary=False, check=True):
        return subprocess.run(base + list(command), check=check, capture_output=True, text=not binary, timeout=45).stdout

    def assert_alive(label):
        if not adb("shell", "pidof", args.package).strip():
            raise RuntimeError(f"Application stopped during {label}")
        report["checks"].append({"step": label, "process_alive": True})

    def snapshot(label):
        # These can contain profile names. Never upload them automatically.
        (args.output / f"{label}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        return tree()

    def tree():
        remote = "/data/local/tmp/nekobox-regression.xml"
        adb("shell", "uiautomator", "dump", remote)
        try:
            return ET.fromstring(adb("shell", "cat", remote))
        finally:
            adb("shell", "rm", "-f", remote, check=False)

    def tap(predicate):
        nodes = list(tree().iter("node"))
        node = next((n for n in nodes if predicate(n)), None)
        if node is None:
            raise RuntimeError("Required UI element is absent")
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds is None:
            raise RuntimeError("UI element has no measured bounds")
        left, top, right, bottom = map(int, bounds.groups())
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(0.5)

    if args.uid_fixture_apk and not args.uid_fixture_package:
        parser.error("--uid-fixture-package is required with --uid-fixture-apk")
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("Invalid app package name")
    if args.uid_fixture_apk:
        badging = subprocess.run([args.aapt2, "dump", "badging", str(args.uid_fixture_apk)], check=True, capture_output=True, text=True, timeout=15).stdout
        match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
        if not match or match.group(1) != args.uid_fixture_package:
            parser.error("Fixture APK package does not match --uid-fixture-package")
    if args.uid_fixture_package == args.package:
        parser.error("UID fixture must be a separate disposable package")
    adb("get-state")
    report["sdk"] = adb("shell", "getprop", "ro.build.version.sdk").strip()
    adb("shell", "am", "start", "-W", "-n", f"{args.package}/io.nekohasekai.sagernet.ui.MainActivity")
    time.sleep(1)
    assert_alive("launch")
    snapshot("launch")
    if args.diagnostics:
        tap(lambda n: n.get("resource-id", "").endswith(":id/shareIcon"))
        tap(lambda n: n.get("text", "").casefold() in {"configuration", "конфигурация"})
        tap(lambda n: "diagnostic" in n.get("text", "").casefold() or "диагност" in n.get("text", "").casefold())
        time.sleep(2)
        snapshot("diagnostics")
        assert_alive("diagnostics")
    wifi = mobile = None
    installed_fixture = False
    try:
        if args.network_cycle:
            wifi = adb("shell", "settings", "get", "global", "wifi_on").strip() == "1"
            mobile = adb("shell", "settings", "get", "global", "mobile_data").strip() == "1"
            adb("shell", "svc", "data", "enable")
            adb("shell", "svc", "wifi", "disable")
            time.sleep(5)
            assert_alive("mobile transition")
            snapshot("mobile")
            adb("shell", "svc", "wifi", "enable")
            time.sleep(5)
            assert_alive("wifi transition")
            snapshot("wifi")
        if args.sleep_wake:
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(2)
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            time.sleep(2)
            assert_alive("sleep/wake")
            snapshot("wake")
        if args.uid_fixture_apk:
            if adb("shell", "pm", "path", args.uid_fixture_package, check=False).strip():
                raise RuntimeError("Fixture already installed; refusing to replace an existing app")
            adb("install", str(args.uid_fixture_apk))
            installed_fixture = True
            time.sleep(2)
            assert_alive("package installation")
            adb("uninstall", args.uid_fixture_package)
            installed_fixture = False
            time.sleep(2)
            assert_alive("package removal")
        crash = adb("logcat", "-b", "crash", "-d", "-v", "brief", "-T", start, check=False)
        # Keep only an outcome, not exception text, endpoints or credentials.
        report["app_crash_detected"] = bool(re.search(r"Process:\s*" + re.escape(args.package) + r"(?:,|\s|$)", crash))
        if report["app_crash_detected"]:
            raise RuntimeError("App crash detected; inspect local device crash buffer")
    finally:
        if wifi is not None:
            adb("shell", "svc", "wifi", "enable" if wifi else "disable", check=False)
        if mobile is not None:
            adb("shell", "svc", "data", "enable" if mobile else "disable", check=False)
        if installed_fixture:
            adb("uninstall", args.uid_fixture_package, check=False)
        (args.output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
