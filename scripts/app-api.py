#!/usr/bin/env python3
"""Control NekoBox over authenticated loopback HTTP or bootstrap a debug install."""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

MAX_RESPONSE = 16 * 1024 * 1024


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {"enabled", "port", "token"}:
        raise ValueError("Invalid API configuration")
    if type(config["enabled"]) is not bool or type(config["port"]) is not int:
        raise ValueError("Invalid API configuration types")
    if not 1024 <= config["port"] <= 65535:
        raise ValueError("Invalid API port")
    if not isinstance(config["token"], str) or not re.fullmatch(r"[0-9a-f]{64}", config["token"]):
        raise ValueError("Invalid API token")
    return config


def save_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=".api-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(value, output)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def adb(serial, *arguments, data=None, check=True):
    result = subprocess.run(
        ["adb", "-s", serial, *arguments], input=data, capture_output=True, timeout=30
    )
    if check and result.returncode:
        raise RuntimeError("adb operation failed; confirm device authorization and a debuggable package")
    return result


def adb_shell(serial, *arguments, data=None, check=True):
    return adb(serial, "shell", shlex.join(arguments), data=data, check=check)


def apply_device_config(args, config):
    # The token goes through stdin, never through command arguments or terminal output.
    adb_shell(
        args.serial, "run-as", args.package, "sh", "-c",
        "umask 077; mkdir -p no_backup && cat > no_backup/local-api.json.tmp "
        "&& mv no_backup/local-api.json.tmp no_backup/local-api.json",
        data=json.dumps(config).encode(),
    )
    adb_shell(args.serial, "am", "start", "-n", f"{args.package}/io.nekohasekai.sagernet.ui.MainActivity")


def configure(args, enabled):
    if not args.serial or not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        raise ValueError("An explicit --serial is required")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        raise ValueError("Invalid package name")
    previous = json.loads(args.state.read_text()) if args.state.exists() else None
    if previous is not None and not isinstance(previous, dict):
        raise ValueError("Invalid client state")
    matching = previous is not None and previous.get("serial") == args.serial and previous.get("package") == args.package
    if enabled and previous is not None and not matching:
        raise ValueError("The saved connection belongs to another device or package; choose another --state path")
    adb_shell(args.serial, "run-as", args.package, "id")
    existing = adb_shell(args.serial, "run-as", args.package, "cat", "no_backup/local-api.json", check=False)
    if existing.returncode == 0:
        config = validate_config(json.loads(existing.stdout))
    else:
        if not enabled:
            absent = adb_shell(args.serial, "run-as", args.package, "sh", "-c", "test ! -e no_backup/local-api.json", check=False)
            if absent.returncode:
                raise RuntimeError("API configuration could not be read; saved access was preserved")
            if matching:
                forward = saved_forward(args, previous)
                if forward is not None:
                    remove_forward(forward)
                args.state.unlink()
            return {"enabled": False}
        config = {"enabled": True, "port": 9091, "token": secrets.token_hex(32)}
    previous_config = config.copy() if existing.returncode == 0 else config | {"enabled": False}
    config["enabled"] = enabled
    if args.rotate:
        config["token"] = secrets.token_hex(32)
    validate_config(config)
    old_forward = saved_forward(args, previous, config["port"]) if matching else None
    if not enabled:
        apply_device_config(args, config)
        if matching:
            if old_forward is not None:
                remove_forward(old_forward)
            args.state.unlink()
        return {"enabled": False}
    reused = old_forward is not None and old_forward[2] == f"tcp:{config['port']}"
    new_forward = None
    try:
        apply_device_config(args, config)
        if reused:
            port = previous["port"]
        else:
            forwarded = adb(args.serial, "forward", "--no-rebind", "tcp:0", f"tcp:{config['port']}")
            port = int(forwarded.stdout.strip())
            new_forward = (args.serial, f"tcp:{port}", f"tcp:{config['port']}")
        state = {"serial": args.serial, "package": args.package, "port": port, "devicePort": config["port"], "token": config["token"]}
        for _ in range(40):
            try:
                result = call(state, "app.status", {})
                break
            except (OSError, RuntimeError):
                time.sleep(0.25)
        else:
            raise RuntimeError("API did not become ready; inspect the API notification and app logs")
        save_private(args.state, state)
    except BaseException:
        restored = False
        try:
            apply_device_config(args, previous_config)
            restored = True
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            pass
        if new_forward is not None:
            try:
                remove_forward(new_forward)
            except (OSError, RuntimeError, subprocess.TimeoutExpired):
                print(f"Warning: could not remove temporary forward {new_forward[1]}", file=sys.stderr)
        if not restored:
            raise RuntimeError("Enrollment failed and the previous device configuration could not be restored; repeat enable to recover") from None
        raise
    if old_forward is not None and not reused:
        try:
            remove_forward(old_forward)
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            print(f"Warning: new connection saved; could not retire previous forward {old_forward[1]}", file=sys.stderr)
    return {"enabled": True, "port": port, "stateFile": str(args.state), "app": result}


def saved_forward(args, previous, default_device_port=None):
    port = previous.get("port")
    device_port = previous.get("devicePort", default_device_port)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("Invalid saved forward")
    # Without a recorded destination, an old forward cannot be proved to belong to this client.
    if device_port is None and "devicePort" not in previous:
        print(f"Warning: destination unknown; unverified forward tcp:{port} was preserved", file=sys.stderr)
        return None
    if type(device_port) is not int or not 1024 <= device_port <= 65535:
        raise ValueError("Invalid saved forward")
    candidate = (args.serial, f"tcp:{port}", f"tcp:{device_port}")
    return candidate if candidate in forward_list(args.serial) else None


def forward_list(serial):
    rows = (tuple(line.split()) for line in adb(serial, "forward", "--list").stdout.decode().splitlines())
    return {row for row in rows if len(row) == 3}


def remove_forward(forward):
    if forward in forward_list(forward[0]):
        adb(forward[0], "forward", "--remove", forward[1])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise RuntimeError("API redirects are refused")


def call(state, operation, parameters):
    if not re.fullmatch(r"[a-z]+\.[a-z]+", operation) or not isinstance(parameters, dict):
        raise ValueError("A command name and a JSON object are required")
    port = state.get("port")
    token = state.get("token")
    if type(port) is not int or not 1 <= port <= 65535 or not isinstance(token, str) or not re.fullmatch(r"[0-9a-f]{64}", token):
        raise ValueError("Invalid client state")
    body = json.dumps(parameters, allow_nan=False).encode()
    if len(body) > 2 * 1024 * 1024:
        raise ValueError("Request exceeds the 2 MiB limit")
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/{operation}", data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=100) as response:
            data = response.read(MAX_RESPONSE + 1)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"API HTTP error {error.code}; the command was not retried") from None
    if len(data) > MAX_RESPONSE:
        raise RuntimeError("API response exceeds the size limit")
    try:
        payload = json.loads(data)
    except ValueError:
        raise RuntimeError("API returned malformed JSON") from None
    if not isinstance(payload, dict) or ("result" in payload) == ("error" in payload):
        raise RuntimeError("API returned a malformed response envelope")
    if "error" in payload:
        raise RuntimeError(f"API {error_text(payload['error'])}")
    return payload["result"]


def error_text(error):
    if not isinstance(error, dict) or not isinstance(error.get("code", "error"), str) or not isinstance(error.get("message", ""), str):
        raise RuntimeError("API returned a malformed error")
    return f"{error.get('code', 'error')}: {error.get('message', '')}"


def smoke(state, mutate=False):
    catalog = call(state, "api.describe", {})
    status = call(state, "app.status", {})
    for operation in ("permissions.get", "profiles.types", "profiles.list", "groups.list", "rules.list", "settings.describe", "automation.get"):
        call(state, operation, {})
    if not mutate:
        return {"commands": len(catalog["commands"]), "readOnly": True}
    if status["serviceState"] != "Stopped":
        raise RuntimeError("Mutation smoke requires a stopped VPN; the client will not stop it")
    group_id = rule_id = None
    try:
        group = call(state, "groups.create", {"name": "API smoke " + secrets.token_hex(6)})
        group_id = group["id"]
        profile = call(state, "profiles.create", {
            "groupId": group_id, "type": 0,
            "configuration": {"name": "API smoke", "serverAddress": "192.0.2.1", "serverPort": 1080},
        })
        call(state, "profiles.update", {"id": profile["id"], "configuration": {"name": "API smoke edited"}})
        result = call(state, "profiles.get", {"id": profile["id"]})
        if result["name"] != "API smoke edited" or "configuration" in result:
            raise RuntimeError("Profile update or redaction check failed")
        rule = call(state, "rules.create", {"values": {"name": "API smoke", "domains": "full:example.invalid", "outbound": profile["id"]}})
        rule_id = rule["id"]
        call(state, "rules.update", {"id": rule_id, "values": {"enabled": False}})
        return {"commands": len(catalog["commands"]), "readOnly": False, "fixtureCrud": "passed"}
    finally:
        if rule_id is not None:
            call(state, "rules.delete", {"id": rule_id, "confirm": True})
        if group_id is not None:
            call(state, "groups.delete", {"id": group_id, "confirm": True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=Path.home() / ".config/nekobox/api.json")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.nb4a.debug")
    commands = parser.add_subparsers(dest="action", required=True)
    for name in ("enable", "disable"):
        commands.add_parser(name).add_argument("--rotate", action="store_true")
    invoke = commands.add_parser("call")
    invoke.add_argument("operation")
    invoke.add_argument("--input", help="JSON file, or - for stdin; defaults to an empty object")
    commands.add_parser("smoke").add_argument("--mutate", action="store_true")
    wait = commands.add_parser("wait")
    wait.add_argument("job_id")
    wait.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    try:
        if args.action in ("enable", "disable"):
            result = configure(args, args.action == "enable")
        else:
            state = json.loads(args.state.read_text())
            if args.serial and state.get("serial") != args.serial:
                raise ValueError("The saved connection belongs to a different device")
            if args.action == "call":
                parameters = {} if args.input is None else json.load(sys.stdin) if args.input == "-" else json.loads(Path(args.input).read_text())
                result = call(state, args.operation, parameters)
            elif args.action == "smoke":
                result = smoke(state, args.mutate)
            else:
                deadline = time.monotonic() + args.timeout
                while True:
                    result = call(state, "jobs.get", {"id": args.job_id})
                    if not isinstance(result, dict) or result.get("state") not in ("running", "succeeded", "failed", "cancelled"):
                        raise RuntimeError("API returned a malformed job status")
                    if result["state"] != "running":
                        if result["state"] != "succeeded":
                            details = f": {error_text(result['error'])}" if "error" in result else ""
                            raise RuntimeError(f"Background operation {result['state']}{details}")
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Job is still running; its operation was not cancelled or retried")
                    time.sleep(1)
        print(json.dumps(result, indent=2, ensure_ascii=False))
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
