# Stored profile fixtures

Captured from the Java beans at commit `8ad8ac0`, with Kryo 5.6.2, before their Kotlin migration. All values are synthetic.

- `profiles.json` freezes 197 stored blobs, their decoded fields, and their canonical re-encoding. Cases cover all 23 concrete bean types, legacy version branches, V2Ray transports, and the version-zero ECH probe.
- `constructors.json` freezes nullable construction, initialized defaults, and public JVM field names and types.
- `subscription-share.hex` freezes the subscription share format.

Later storage versions extend these files by hand rather than by capture:

- `WireGuardBean-v3`, `WireGuardBean-v3-defaults`, `AmneziaWGBean-v1` and `AmneziaWGBean-v1-defaults` were written from the Kryo byte layout for the AllowedIPs, keepalive, extra peer, peer position, imported DNS and DNS override fields. A version whose new fields hold their defaults re-encodes in the previous version's layout, so the `-defaults` cases have the older `canonicalHex`. The captured WireGuard and AmneziaWG cases gained only the default values of those fields in `decoded`.

Tests must consume these bytes, not generate replacements from the implementation under test. A changed fixture requires an explicit storage-format migration review.
