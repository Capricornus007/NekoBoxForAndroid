import contextlib
import importlib.util
import json
import io
from pathlib import Path
from types import SimpleNamespace
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("app_api", Path(__file__).with_name("app-api.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
TOKEN = "0123456789abcdef" * 4
DEFAULT_RESPONSE = object()


class ClientTests(unittest.TestCase):
    def test_config_is_strict_and_cannot_select_a_lan_bind(self):
        valid = {"enabled": True, "port": 9091, "token": TOKEN}
        self.assertEqual(valid, api.validate_config(valid.copy()))
        for patch_value in ({"enabled": "true"}, {"port": True}, {"port": 65536}, {"port": "9091"}, {"token": "short"}, {"host": "0.0.0.0"}):
            with self.subTest(value=patch_value), self.assertRaises(ValueError):
                api.validate_config(valid | patch_value)

    def test_credentials_are_saved_privately_and_replaced_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            api.save_private(path, {"token": TOKEN})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            api.save_private(path, {"token": "replacement"})
            self.assertEqual("replacement", json.loads(path.read_text())["token"])
            self.assertEqual([path], list(Path(directory).iterdir()))

    @contextlib.contextmanager
    def server(self, code=200, payload=DEFAULT_RESPONSE, redirect=False, raw=None):
        received = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.path, self.headers.get("Authorization"), self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(code)
                if redirect:
                    self.send_header("Location", "https://example.invalid/collect")
                self.end_headers()
                body = {"result": {"ok": True}} if payload is DEFAULT_RESPONSE else payload
                self.wfile.write(json.dumps(body).encode() if raw is None else raw)

            def log_message(self, *_):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever)
        worker.start()
        try:
            yield {"port": server.server_port, "token": TOKEN}, received
        finally:
            server.shutdown()
            worker.join()
            server.server_close()

    def test_call_uses_loopback_bearer_and_ignores_environment_proxies(self):
        with self.server() as (state, received), patch.dict("os.environ", {"http_proxy": "http://example.invalid:8080", "no_proxy": ""}):
            self.assertEqual({"ok": True}, api.call(state, "profiles.list", {"limit": 3}))
            self.assertEqual("/v1/profiles.list", received[0][0])
            self.assertEqual("Bearer " + TOKEN, received[0][1])
            self.assertEqual({"limit": 3}, json.loads(received[0][2]))

    def test_call_does_not_follow_redirects_or_retry_mutations(self):
        with self.server(code=302, redirect=True) as (state, received):
            with self.assertRaisesRegex(RuntimeError, "redirect"):
                api.call(state, "profiles.create", {})
            self.assertEqual(1, len(received))
        with self.server(code=401) as (state, received):
            with self.assertRaisesRegex(RuntimeError, "401"):
                api.call(state, "profiles.create", {})
            self.assertEqual(1, len(received))

    def test_application_errors_are_not_success(self):
        with self.server(payload={"error": {"code": "confirmation_required"}}) as (state, _):
            with self.assertRaisesRegex(RuntimeError, "confirmation_required"):
                api.call(state, "profiles.delete", {})

    def test_malformed_response_shapes_raise_defined_errors_without_echoing_content(self):
        for payload in (None, [], "private-response", {}, {"error": None}, {"error": []},
                        {"error": {"code": 3}}, {"error": {"message": []}},
                        {"result": {}, "error": {"code": "invalid"}}):
            with self.subTest(payload=payload), self.server(payload=payload) as (state, received):
                with self.assertRaisesRegex(RuntimeError, "malformed") as raised:
                    api.call(state, "app.status", {})
                self.assertNotIn("private-response", str(raised.exception))
                self.assertEqual(1, len(received))
        for raw in (b"{private-response", b"\xff"):
            with self.server(raw=raw) as (state, _), self.assertRaisesRegex(RuntimeError, "malformed JSON"):
                api.call(state, "app.status", {})
        with self.server(payload={"result": None}) as (state, _):
            self.assertIsNone(api.call(state, "app.status", {}))

    @contextlib.contextmanager
    def enrollment(self, saved=None, forwards=(), device_port=9091):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(serial="emulator-5554", package="com.example.debug", state=Path(directory) / "state.json", rotate=False)
            if saved is not None:
                api.save_private(args.state, saved)
            rows = set(forwards)
            calls = []
            config = {"enabled": True, "port": device_port, "token": TOKEN}
            args.device_config = config

            def adb(serial, *arguments, **_):
                calls.append((serial, *arguments))
                if arguments == ("forward", "--list"):
                    data = "\n".join(" ".join(row) for row in sorted(rows)).encode()
                elif arguments[:3] == ("forward", "--no-rebind", "tcp:0"):
                    rows.add((serial, "tcp:42001", arguments[3]))
                    data = b"42001\n"
                elif arguments[:2] == ("forward", "--remove"):
                    rows.difference_update({row for row in rows if row[0] == serial and row[1] == arguments[2]})
                    data = b""
                else:
                    self.fail(f"Unexpected adb operation: {arguments}")
                return SimpleNamespace(returncode=0, stdout=data)

            def shell(*_, data=None, **__):
                if data is not None:
                    config.clear()
                    config.update(json.loads(data))
                return SimpleNamespace(returncode=0, stdout=json.dumps(config).encode())

            with patch.object(api, "adb", side_effect=adb), patch.object(api, "adb_shell", side_effect=shell), \
                    patch.object(api, "call", return_value={"package": args.package}) as invoke, patch.object(api.time, "sleep"):
                yield args, rows, calls, invoke

    @contextlib.contextmanager
    def missing_device_config(self, still_exists=False):
        shell = api.adb_shell

        def read(*arguments, **kwargs):
            if arguments[-2:] == ("cat", "no_backup/local-api.json"):
                return SimpleNamespace(returncode=1, stdout=b"")
            if arguments[-1] == "test ! -e no_backup/local-api.json":
                return SimpleNamespace(returncode=int(still_exists), stdout=b"")
            return shell(*arguments, **kwargs)

        with patch.object(api, "adb_shell", side_effect=read):
            yield

    def test_disable_cleans_owned_host_state_when_device_config_is_missing(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        owned = ("emulator-5554", "tcp:42000", "tcp:9091")
        foreign = ("another-device", "tcp:43000", "tcp:9091")
        with self.enrollment(previous, [owned, foreign]) as (args, rows, _, invoke), self.missing_device_config():
            self.assertEqual({"enabled": False}, api.configure(args, False))
            self.assertEqual({foreign}, rows)
            self.assertFalse(args.state.exists())
            invoke.assert_not_called()

    def test_missing_device_config_does_not_authorize_foreign_host_cleanup(self):
        foreign = ("emulator-5554", "tcp:42000", "tcp:9999")
        for other_owner in (True, False):
            previous = {"serial": "another-device" if other_owner else "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
            with self.subTest(other_owner=other_owner), self.enrollment(previous, [foreign]) as (args, rows, calls, _), self.missing_device_config():
                api.configure(args, False)
                self.assertEqual({foreign}, rows)
                self.assertEqual(other_owner, args.state.exists())
                self.assertFalse(any("--remove" in call for call in calls))

    def test_missing_config_with_legacy_state_keeps_unverifiable_forward(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "token": TOKEN}
        forwarding = ("emulator-5554", "tcp:42000", "tcp:9091")
        warning = io.StringIO()
        with self.enrollment(previous, [forwarding]) as (args, rows, _, _), self.missing_device_config(), contextlib.redirect_stderr(warning):
            api.configure(args, False)
            self.assertEqual({forwarding}, rows)
            self.assertFalse(args.state.exists())
        self.assertIn("unverified forward", warning.getvalue())
        self.assertNotIn(TOKEN, warning.getvalue())

    def test_missing_config_rejects_a_null_saved_destination(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": None, "token": TOKEN}
        forwarding = ("emulator-5554", "tcp:42000", "tcp:9091")
        with self.enrollment(previous, [forwarding]) as (args, rows, _, _), self.missing_device_config():
            with self.assertRaisesRegex(ValueError, "Invalid saved forward"):
                api.configure(args, False)
            self.assertEqual({forwarding}, rows)
            self.assertTrue(args.state.exists())

    def test_unreadable_existing_device_config_keeps_saved_access(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        forwarding = ("emulator-5554", "tcp:42000", "tcp:9091")
        with self.enrollment(previous, [forwarding]) as (args, rows, _, _), self.missing_device_config(still_exists=True):
            with self.assertRaisesRegex(RuntimeError, "saved access was preserved"):
                api.configure(args, False)
            self.assertEqual({forwarding}, rows)
            self.assertEqual(previous, json.loads(args.state.read_text()))

    def test_forward_listing_ignores_blank_and_malformed_rows(self):
        output = b"\nemulator-5554 tcp:42000 tcp:9091\n\npartial row\n"
        with patch.object(api, "adb", return_value=SimpleNamespace(stdout=output)):
            self.assertEqual({("emulator-5554", "tcp:42000", "tcp:9091")}, api.forward_list("emulator-5554"))

    def test_enrollment_reuses_its_forward_across_rotation_and_disable(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "token": TOKEN}
        forwarding = ("emulator-5554", "tcp:42000", "tcp:9091")
        with self.enrollment(previous, [forwarding]) as (args, rows, calls, _):
            args.rotate = True
            self.assertEqual(42000, api.configure(args, True)["port"])
            current = json.loads(args.state.read_text())
            self.assertNotEqual(TOKEN, current["token"])
            self.assertEqual(9091, current["devicePort"])
            self.assertFalse(any("--no-rebind" in command for command in calls))
            api.configure(args, False)
            self.assertFalse(rows)
            self.assertFalse(args.state.exists())

    def test_replacement_removes_only_the_old_owned_forward(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        old = ("emulator-5554", "tcp:42000", "tcp:9091")
        foreign = ("another-device", "tcp:43000", "tcp:9091")
        with self.enrollment(previous, [old, foreign], device_port=9191) as (args, rows, _, _):
            self.assertEqual(42001, api.configure(args, True)["port"])
            self.assertEqual({foreign, (args.serial, "tcp:42001", "tcp:9191")}, rows)

    def test_failed_replacement_keeps_previous_state_and_cleans_new_forward(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        old = ("emulator-5554", "tcp:42000", "tcp:9091")
        with self.enrollment(previous, [old], device_port=9191) as (args, rows, _, invoke):
            invoke.side_effect = RuntimeError("not ready")
            with self.assertRaisesRegex(RuntimeError, "did not become ready"):
                api.configure(args, True)
            self.assertEqual({old}, rows)
            self.assertEqual(previous, json.loads(args.state.read_text()))

    def test_state_save_failure_preserves_old_forward_and_discards_new_forward(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        old = ("emulator-5554", "tcp:42000", "tcp:9091")
        with self.enrollment(previous, [old], device_port=9191) as (args, rows, _, _):
            with patch.object(api, "save_private", side_effect=OSError("disk full")), self.assertRaises(OSError):
                api.configure(args, True)
            self.assertEqual({old}, rows)
            self.assertEqual(previous, json.loads(args.state.read_text()))

    def test_old_forward_cleanup_failure_keeps_the_saved_new_connection(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        old = ("emulator-5554", "tcp:42000", "tcp:9091")
        with self.enrollment(previous, [old], device_port=9191) as (args, rows, _, _):
            warning = io.StringIO()
            with patch.object(api, "remove_forward", side_effect=RuntimeError("cleanup failed")), contextlib.redirect_stderr(warning):
                result = api.configure(args, True)
            self.assertTrue(result["enabled"])
            self.assertIn("Warning:", warning.getvalue())
            self.assertIn("tcp:42000", warning.getvalue())
            self.assertEqual(42001, json.loads(args.state.read_text())["port"])
            self.assertIn((args.serial, "tcp:42001", "tcp:9191"), rows)
            self.assertIn(old, rows)

    def test_failed_rotation_restores_device_credentials_on_a_reused_forward(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        old = ("emulator-5554", "tcp:42000", "tcp:9091")
        for failure in ("readiness", "save"):
            with self.subTest(failure=failure), self.enrollment(previous, [old]) as (args, rows, _, invoke):
                original = args.device_config.copy()
                args.rotate = True
                if failure == "readiness":
                    invoke.side_effect = RuntimeError("not ready")
                    with self.assertRaises(RuntimeError):
                        api.configure(args, True)
                else:
                    with patch.object(api, "save_private", side_effect=OSError("disk full")), self.assertRaises(OSError):
                        api.configure(args, True)
                self.assertEqual(original, args.device_config)
                self.assertEqual(previous, json.loads(args.state.read_text()))
                self.assertEqual({old}, rows)

    def test_failed_rotation_restores_config_if_forward_allocation_fails(self):
        with self.enrollment() as (args, rows, _, _):
            original = args.device_config.copy()
            args.rotate = True
            with patch.object(api, "adb", side_effect=RuntimeError("forward failed")), self.assertRaisesRegex(RuntimeError, "forward failed"):
                api.configure(args, True)
            self.assertEqual(original, args.device_config)
            self.assertFalse(args.state.exists())
            self.assertFalse(rows)

    def test_failed_rotation_reports_unrecoverable_device_config_without_secrets(self):
        with self.enrollment() as (args, rows, _, invoke):
            args.rotate = True
            invoke.side_effect = RuntimeError("not ready")
            with patch.object(api, "apply_device_config", side_effect=[None, RuntimeError("restore failed")]), self.assertRaisesRegex(RuntimeError, "could not be restored") as failure:
                api.configure(args, True)
            self.assertNotIn(TOKEN, str(failure.exception))
            self.assertFalse(rows)

    def test_stale_state_does_not_delete_a_foreign_forward(self):
        previous = {"serial": "emulator-5554", "package": "com.example.debug", "port": 42000, "devicePort": 9091, "token": TOKEN}
        foreign = ("emulator-5554", "tcp:42000", "tcp:9999")
        with self.enrollment(previous, [foreign]) as (args, rows, calls, _):
            api.configure(args, True)
            self.assertIn(foreign, rows)
            self.assertNotIn((args.serial, "forward", "--remove", "tcp:42000"), calls)

    def test_enrollment_cannot_overwrite_another_device_state(self):
        previous = {"serial": "another-device", "package": "com.example.debug", "port": 42000, "token": TOKEN}
        with self.enrollment(previous) as (args, _, calls, _):
            with self.assertRaisesRegex(ValueError, "another device"):
                api.configure(args, True)
            self.assertFalse(calls)
            self.assertEqual(previous, json.loads(args.state.read_text()))

    def test_invalid_calls_fail_before_network_io(self):
        state = {"port": 9091, "token": TOKEN}
        for operation, params in (("../settings", {}), ("profiles.list", []), ("profiles.list", {"x": float("nan")})):
            with self.subTest(operation=operation), self.assertRaises(ValueError):
                api.call(state, operation, params)
        with self.assertRaises(ValueError):
            api.call(state, "profiles.list", {"text": "x" * (2 * 1024 * 1024)})

    def test_wait_reports_the_job_error_code_and_message(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            api.save_private(path, {"port": 9091, "token": TOKEN})
            response = {"state": "failed", "error": {"code": "confirmation_required", "message": "Approval is required"}}
            output = io.StringIO()
            with patch.object(api.sys, "argv", ["app-api.py", "--state", str(path), "wait", "fixture"]), patch.object(api, "call", return_value=response), contextlib.redirect_stderr(output):
                self.assertEqual(1, api.main())
            self.assertIn("confirmation_required", output.getvalue())
            self.assertIn("Approval is required", output.getvalue())

    def test_wait_rejects_malformed_job_status_without_a_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            api.save_private(path, {"port": 9091, "token": TOKEN})
            for response in (None, [], {}, {"state": "unknown"}, {"state": "failed", "error": []}):
                output = io.StringIO()
                with self.subTest(response=response), patch.object(api.sys, "argv", ["app-api.py", "--state", str(path), "wait", "fixture"]), patch.object(api, "call", return_value=response), contextlib.redirect_stderr(output):
                    self.assertEqual(1, api.main())
                self.assertIn("malformed", output.getvalue())
                self.assertNotIn("Traceback", output.getvalue())

    def test_smoke_does_not_mutate_or_stop_a_running_or_unknown_vpn(self):
        invoked = []
        service_state = "Connected"

        def call(_, operation, __):
            invoked.append(operation)
            if operation == "api.describe":
                return {"commands": {"app.status": {}}}
            if operation == "app.status":
                return {"serviceState": service_state}
            return {}

        with patch.object(api, "call", side_effect=call):
            self.assertTrue(api.smoke({}, mutate=False)["readOnly"])
            for service_state in ("Connected", "Idle", "Connecting", "Stopping"):
                with self.subTest(state=service_state), self.assertRaisesRegex(RuntimeError, "stopped VPN"):
                    api.smoke({}, mutate=True)
        self.assertNotIn("groups.create", invoked)
        self.assertNotIn("service.stop", invoked)


if __name__ == "__main__":
    unittest.main()
