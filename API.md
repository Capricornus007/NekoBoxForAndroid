# Local control API

The app provides a versioned HTTP API for local automation. It listens on `127.0.0.1:9091` while enabled, independently of the VPN. It is off by default.

The token grants administrative access to the app, including credential exports, backup restoration and approval of installed plugin signers. Keep it out of source control, shell history and shared logs. Disable the API or replace its token to revoke access. An operation accepted before revocation may finish.

## Enable access

For a release build, open Settings → Advanced → Local control API. Enable access and copy the token. The ongoing notification has a Disable API action.

For an installed debug build with authorized ADB access, the Python client enrolls the app without screen taps:

```sh
python3 scripts/app-api.py --serial DEVICE_SERIAL enable
python3 scripts/app-api.py call app.status
python3 scripts/app-api.py call api.describe
python3 scripts/app-api.py call api.openapi > openapi.json
```

The client requires Python 3.9 or later and `adb`. `--package` defaults to `com.nb4a.debug`. It writes only the app's private `no_backup/local-api.json`, launches `MainActivity`, and creates an ADB port forward on a free host port. Repeated enrollment reuses a matching forward, including during token rotation. It does not install, uninstall, clear data, stop the VPN or grant Android permissions.

The client saves the forwarded port and token in `~/.config/nekobox/api.json` with mode `0600`. Use a different `--state` path for each device. The app keeps its API credentials outside Android backups and its own exported backups.

```sh
python3 scripts/app-api.py --serial DEVICE_SERIAL enable --rotate
python3 scripts/app-api.py --serial DEVICE_SERIAL disable
```

Disabling also removes matching saved host credentials and an owned ADB forward when the device configuration is missing. If an existing configuration cannot be read, the client preserves saved access and reports an error. Legacy state without a recorded forward destination leaves that unverified listener in place with a warning.

Release builds do not permit `run-as`. After enabling the API in the app, use an explicit forward such as `adb -s DEVICE_SERIAL forward tcp:19091 tcp:9091`, then connect to `http://127.0.0.1:19091` with the copied token. A client state file can contain `{"port":19091,"token":"YOUR_TOKEN"}` for HTTP-only use. Keep that file private.

## Requests and results

Send commands as `POST /v1/<command>` with an object body, `Content-Type: application/json`, and `Authorization: Bearer <token>`. `GET /v1` returns the command catalog. Authentication applies to discovery too.

Successful command responses contain `result`. Application failures contain `error` with a stable `code` and an optional explanation. Both use HTTP 200. Transport failures use HTTP 400, 401, 403, 404, 413, 415, 500 or 503. Check the envelope, not just the status code.

```json
{"result":{"selectedProfileId":12}}
```

```json
{"error":{"code":"confirmation_required","message":"Repeat with confirm=true to authorize this change"}}
```

`api.describe` lists the accepted arguments and input schema for each command. `api.openapi` generates an OpenAPI 3.1 document from the same registry. Unknown arguments, wrong primitive types and unknown writable fields fail before changes are applied. IDs are JSON integers except job and recovery IDs, which are strings.

Requests are limited to 2 MiB, 64 nesting levels and 100,000 JSON tokens. Responses are limited to 16 MiB. Browser requests and non-loopback Host headers are refused. The API does not support CORS, cookies, redirects or tokens in URLs. Use the literal address `127.0.0.1`.

Commands execute one at a time. HTTP 503 with `error.code: busy` and `Retry-After: 1` means the request was not dispatched because another command was executing. You can retry after that delay. An HTTP 200 `busy` error means another command or background job is still running, including a command accepted before token rotation. Wait before retrying, or poll the job when one exists.

Long operations return a `jobId`; poll `jobs.get`, or use:

```sh
python3 scripts/app-api.py wait JOB_ID
```

The app retains the latest 16 jobs until the API service stops. During a job, status reads, service shutdown and Tailscale cancellation/closure remain available. Other commands report `busy`. Jobs have `running`, `succeeded`, `failed` or `cancelled` states. Do not retry a mutation after a connection failure without first checking the app's state.

## Command coverage

| Area | Commands |
| --- | --- |
| Discovery and lifecycle | `api.describe`, `api.openapi`, `app.status`, `app.restart`, `permissions.get`, `jobs.get` |
| Protocol profiles | `profiles.types`, `profiles.list`, `profiles.get`, `profiles.create`, `profiles.update`, `profiles.clone`, `profiles.import`, `profiles.export`, `profiles.select`, `profiles.delete`, `profiles.reorder`, `profiles.test` |
| Groups and subscriptions | `groups.list`, `groups.get`, `groups.create`, `groups.update`, `groups.delete`, `groups.reorder`, `subscriptions.describe`, `subscriptions.update` |
| VPN and proxy service | `service.start`, `service.stop`, `service.reload`, `service.traffic`, `service.connections`, `service.resetconnections`, `service.test` |
| Routing rules | `rules.list`, `rules.create`, `rules.update`, `rules.delete`, `rules.reorder` |
| Saved routing profiles | `routing.list`, `routing.save`, `routing.select`, `routing.rename`, `routing.delete`, `routing.export`, `routing.import` |
| Settings and per-app routing | `settings.describe`, `settings.get`, `settings.set`, `apps.list` |
| Network automation and location | `automation.get`, `automation.set`, `location.clear` |
| Tailscale and WireGuard | `tailscale.running`, `tailscale.open`, `tailscale.status`, `tailscale.close`, `tailscale.ping`, `tailscale.exit`, `tailscale.result`, `tailscale.cancel`, `wireguard.status` |
| Routing assets | `assets.list`, `assets.import`, `assets.download`, `assets.delete` |
| Backups | `backup.export`, `backup.inspect`, `backup.restore`, `backup.recoveries`, `backup.recovery` |
| WebDAV | `webdav.list`, `webdav.upload`, `webdav.download` |
| Sharing and diagnostics | `sharing.get`, `sharing.rotate`, `diagnostics.stun`, `diagnostics.icmp`, `logs.read`, `logs.clear` |
| Plugin trust | `plugins.inspect`, `plugins.approve` |
| App screens | `ui.status`, `ui.navigate`, `ui.recreate` |

### Profiles and groups

`profiles.types` describes the persisted fields for every protocol in the app's registry. Use its numeric `type` and field schema for creation. VMess/VLESS and Hysteria versions use the same switches as their profile editors. Transient runtime fields, database identities and traffic counters are not writable configuration.

`profiles.list` returns metadata without protocol credentials. Its optional `groupId`, `offset` and `limit` arguments support pagination; the default limit is 200 and the maximum is 1,000. `profiles.get` includes the configuration only with `includeSecrets: true`. Profile exports and backup exports require that flag too.

Create a group, then use the returned ID to create a profile. Supply JSON through files or stdin rather than putting credentials in command arguments:

```sh
printf '%s' '{"name":"Local profiles"}' |
  python3 scripts/app-api.py call groups.create --input -
```

Example profile creation body, with `groupId` replaced by that result:

```json
{
  "groupId": 1,
  "type": 0,
  "configuration": {
    "name": "Example SOCKS proxy",
    "serverAddress": "192.0.2.1",
    "serverPort": 1080
  }
}
```

`profiles.update` patches `configuration`; it does not replace the profile's identity or counters. `profiles.import` accepts share-link text and imports into a basic group. `profiles.clone` also requires a basic target group. Reorder commands require a complete, duplicate-free list of the current IDs.

To create a subscription, supply a `subscription` object to `groups.create`. Use `subscriptions.describe` for its field types. Provider metadata is read-only; the writable fields cover the URL, approved backup URLs, response format, filtering, DNS resolution, automatic updates, User-Agent and device-ID transmission. `subscriptions.update` runs without app dialogs. A warning that needs approval returns `confirmation_required`; `confirm: true` authorizes the update warnings.

Profile and group deletion requires `confirm: true` and a stopped service. The API refuses to delete profiles referenced by chains, groups, routing rules or network automation. It also protects the default group.

### Service and protocol diagnostics

`service.start` accepts an optional profile `id`. `profiles.select` changes the saved selection; `apply: true` also switches a running service. `service.reload` applies saved configuration. Start, stop and reload return acceptance, not completion. Poll `app.status.serviceState`. `Idle` means the service connection is not ready; wait for a known state before starting the service or issuing operations that require it to be stopped.

Use `service.test` for the running configuration, and `profiles.test` for a saved profile through the app's existing isolated probe implementation. Both return jobs. `service.traffic` reports the latest traffic sample; it remains empty before the first sample or when traffic sampling is disabled.

For Tailscale, open a session with `tailscale.open` and poll `tailscale.status`. `check: true` permits a managed check of a stopped profile. Peer probes and exit-node changes return request IDs for `tailscale.result`. Exit changes require the exact `expectedSavedSelection` and `confirm: true`. Close sessions when finished. The API permits two sessions, admits at most four in-flight requests per session, and retains up to 64 requests. Peer IDs are limited to 256 UTF-16 code units and exit-selection baselines to 4096. Closing a session discards its requests. Login URLs remain data. The API does not open them.

Requests for a `closed` or `error` session fail with `session_closed`. If the session closes or a result does not arrive within 60 seconds, `tailscale.result` returns `done: true`, `state: "unavailable"` and an `errorCode`. On timeout the API also requests cancellation. An unavailable result does not prove that an exit-node change was rolled back; refresh the node status before retrying. A late authoritative result can replace the unavailable reply, but late progress cannot return it to pending.

### Settings, routing and screens

`settings.describe` lists writable settings with their storage types and choices. Several numeric preference values, including ports and enum selections, use strings because the existing preference store does. `settings.set` accepts a `values` object and validates the whole patch before writing. Internal state and plugin approval keys are not generic writable settings.

The API requires confirmation before enabling LAN sharing, insecure TLS options, unauthenticated system HTTP proxying or mock location. Android permissions remain necessary. `sharing.get` reveals the shared proxy credential only with `includeSecrets: true`; `sharing.rotate` changes that credential and requires a subsequent reload.

Settings and routing changes report whether a reload or restart is needed. Use `service.reload` to apply core configuration, `ui.recreate` for appearance, or `app.restart` for process-level settings such as logging. Restart requires `confirm: true` and a stopped service. Changes to `hideFromRecentApps` take effect at once for both true and false.

Use `automation.set` to replace the network-rule list. Each rule has `kind`, `action`, and optional `ssid` and `profileId`. Kinds are `MOBILE`, `WIFI`, `SSID` and `ETHERNET`; actions are `CONNECT` and `DISCONNECT`. Enable or disable automation through `settings.set`.

`ui.status` lists the available screen names. `ui.navigate` accepts one of those names. Launch the main activity first if the API reports `ui_unavailable`. Screen navigation never opens the web dashboard or another browser.

### Assets, backups and external services

`assets.import` takes a basename ending in `.db` and base64 content up to 1,500,000 decoded bytes. `assets.download` accepts an HTTPS URL and required SHA-256 checksum, with a 256 MiB download limit. Both replace files atomically, after validation. Replacing an existing asset requires confirmation. The bundled `geoip.db` and `geosite.db` cannot be deleted through this API.

`backup.inspect` validates version-2 backup sections without writing. `backup.restore` requires explicit `profiles`, `rules` and `settings` booleans, `confirm: true`, and a stopped service. It decodes the selected sections before mutation, writes and verifies a private recovery backup, then calls the existing restore coordinator. It never imports plugin signer approvals. Read the returned `recoveryId` through `backup.recovery` with `includeSecrets: true`. `backup.recoveries` lists retained recovery copies. Recovery copies contain credentials and stay in the app's private non-backup directory.

WebDAV commands use the saved WebDAV settings, require HTTPS and refuse redirects. Upload requires both confirmation and explicit secret access. Download returns backup content as a job result; it does not restore it. Pass the downloaded content to `backup.inspect` before any explicit restore.

`plugins.inspect` returns the current rejected package identities and signing fingerprints for a known plugin. Approval requires confirmation and an exact match to one unambiguous installed plugin. The API cannot install an APK or write arbitrary trust entries.

## Android permission boundaries

The API does not grant VPN consent, location permissions, mock-location authorization, always-on VPN or lockdown settings. `permissions.get` reports these conditions without showing dialogs or taking over another VPN. A missing permission returns `permission_required`. Android may also refuse a service start while the app is in the background; bring the main activity forward through ADB before retrying.

Camera capture, the Android document picker, package installation and external account login remain platform interactions. Use profile text import, JSON backups and asset upload/download instead of camera or file-picker interaction. There is no factory-reset or arbitrary shell/file-access command.

## Exercising the API

The client has a read-only smoke check:

```sh
python3 scripts/app-api.py smoke
```

On a disposable test install with the VPN stopped, `smoke --mutate` creates a uniquely named group, profile and routing rule, checks edits and credential redaction, then removes only those fixtures. It refuses to stop a running VPN. Run mutation checks only on disposable test installs. A failure can leave its named fixtures for inspection; it never clears app data.

The automated suite covers the HTTP authentication and browser boundaries, request limits, command input contracts, protocol field round-trips, handler CRUD, settings validation, recovery backups, WebDAV parsing and the Python client. The device scenario uses a disposable Android emulator for HTTP authentication and profile CRUD. Hardware-dependent VPN behavior and live external accounts require separate integration environments.
