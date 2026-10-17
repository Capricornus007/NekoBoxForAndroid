# Material 3 Expressive: dev.14 interface audit

Scope: every application activity in `AndroidManifest.xml`, all 50 layout resources,
23 preference schemas, shared themes/dialogs, and the five code-built view families.
This is a source/resource audit. No Android device or emulator is attached; visual
rendering, TalkBack, OEM behavior and measured frame performance remain device checks.

## Findings and changes

| Surface | Finding / decision |
| --- | --- |
| Shared app bar: configuration, groups, routes, assets, tools, editors | Fixed 64dp app-bar height included the status-bar inset and reduced space for the toolbar. Container now wraps its content; toolbar retains its 64dp height. Drawer button has an accessibility label. |
| Main connection panel and profile cards | Existing emphasized connection action, semantic selection container, readable metadata and 48dp actions are appropriate. Preserved the connection flow and stable card layout. |
| Navigation drawer, tabs, profile picker and switch dialog | Existing drawer is suitable for the number of destinations; no navigation-system migration. Checked the Expressive selection shapes, labels, themes and shared profile rows. |
| General settings; every protocol, route, subscription and WebDAV preference editor | All share the Material preference templates. Preference titles can now grow beyond two lines. Existing multiline value editors and Material switches are retained. |
| Blue/green/pink, dark, AMOLED and dynamic themes | Accent overlays previously supplied light-mode color roles even in dark mode. Added night-specific paired roles and the missing lowest/highest surface containers. Fixed palette text-role pairs are checked at >=4.5:1; runtime dynamic colors still need device verification. |
| Group/subscription list | Name and user information are stacked so long text does not compete for a single horizontal strip. Existing update indicator and menu actions remain. |
| Backup, import, network tools, application lists, route actions, switches | Fixed 48dp heights could cut off larger text. Controls now grow with a minimum 48dp touch target. Explicit legacy filter chips use Material 3 styles. |
| Configuration removal, backup restore, STUN result dialogs | Replaced remaining AppCompat builders in themed application screens with Material builders. Existing shared dialog fragments already use Material. |
| JSON editor and extended keyboard | 36dp targets were too small. Keys and actions now use 48dp targets, semantic container/foreground colors and action labels. Character strip takes remaining width so undo/redo/format remain reachable. Code content stays monospace. |
| STUN | Replaced the bare text input with an outlined Material field, removed false clickability from passive cards, used semantic surfaces/type and a filled primary Start action. Results remain selectable. |
| Diagnostics | Existing full-screen grouped surfaces and explicit primary checks retained. Added 10-minute history, server-side correlated filters, older/latest page actions, truncation notice and paused-check state. |
| Automatic network profiles | Existing Material cards, outlined fields and primary/secondary action hierarchy retained; large-text switch sizing updated through common resources. |
| Assets, route list, app allowlist and UID selector | Existing Material cards, 48dp actions, list typography and scrolling retained; common app-bar and adaptive-control fixes apply. |
| Log, progress/loading, debug tools, QR and About | Existing Material progress components and common styles checked. Technical log/code text remains monospace. QR pixels stay black/white for scanning; surrounding controls use application theming. |
| Color picker | Existing Material dialog, 64dp color targets and shared preferences checked. |
| Scanner and externally supplied dashboard/WebView content | Keep camera readability and external content ownership; app-owned bars/menus inherit shared fixes. |
| VPN system consent, blank trampoline and translucent shortcuts | System/native/transient surfaces are intentionally not restyled as application pages. Shortcut confirmations retain their platform theme. |

Expressive design is applied through action emphasis, semantic color roles,
grouping, appropriate shape/typography and accessible controls. No decorative
motion, new UI framework or additional runtime dependency was introduced.

## dev.14.1 scrollbar correction

A user recording exposed jitter in routes and profiles that the static audit did
not detect. The existing FastScroll 2.0.1 widget estimated the entire list from
the first visible card height, omitted card margins, and used a different padded
track for drawing and dragging. Shared list layouts now use row-based progress
from both visible edges, decorated bounds including margins, and the same track
for thumb input/output. Existing thumb styling, animations and app-name popup are
reused; no dependency was added. Grid rows and a partial final row are supported.
Seeking corrects the target against its actual laid-out height before drawing,
including a final card taller than the viewport. Thumb capture prevents a
ViewPager2 group swipe. JVM regression checks cover continuity, track round trips,
row/grid seeking, viewport boundaries and a tall final card. Device replay is
still required; no Android target is attached here.

## Power and diagnostic behavior

- Health checks stop when the display is off, the keyguard is locked or Android
  enters Doze. Unlock resumes active group checks. Pending checks are cancelled;
  cancellation does not count as a failed fallback round.
- Screen lock uses an independent probe pause. It does not put the VPN device
  down. Existing Android Doze handling is kept separate.
- Manual DNS/UDP/TCP/TLS/MTU checks are cancelled on lock and rejected while paused.
- Traffic counters use at least 30 seconds between background reads; screen/keyguard
  events wake the counter loop immediately. Native cumulative deltas are retained.
- Transport keepalives, DNS caching and VPN forwarding are preserved. Subscription
  refreshes already use deferrable WorkManager jobs; no exact wake alarm was added.
- History is collected only at debug/trace, kept in memory for ten minutes and
  expired lazily without a cleanup timer. Pages are bounded for Android IPC.
  The safety ceiling is 16,384 events and an 8 MiB accounted data budget; a visible
  notice identifies early eviction during unusually large bursts.

## Verification

Native tests cover time-window boundaries, bursts, page ordering/gaps, filtering
across pages, exact Android UIDs, uncorrelated DNS, active-check cancellation,
fallback health preservation, manual-check cancellation and VPN-device isolation.
Existing DNS bootstrap, route fallback, TUN owner and WG/AWG tests remain required.
JVM checks cover keyguard/Doze power decisions and delayed counter accumulation.
All resource XML parses; fixed accent color pairs pass contrast calculations.

Before sign-off on a device, inspect all destinations in RU/EN, light/dark/AMOLED/
dynamic color, 100%/200% font sizes, portrait/landscape, gesture/three-button navigation,
and TalkBack. Run the adb smoke scenario, lock during checks, then verify unlock,
Wi-Fi/mobile transitions, traffic totals and work-profile isolation.

## Official guidance

- https://m3.material.io/foundations/usability/applying-m-3-expressive
- https://m3.material.io/develop/android/mdc-android
- https://design.google/library/expressive-material-design-google-research
- https://developer.android.com/guide/topics/ui/accessibility/apps
- https://developer.android.com/training/monitoring-device-state/doze-standby
