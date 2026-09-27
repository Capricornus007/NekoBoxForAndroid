"""Temporary helper: merge a translated fragment into a locale strings.xml.

- Appends fragment entries just before </resources>.
- Removes entries whose names are passed via --drop (must be gone from locale).

Run from repo root:
    uv run tools/diagnostics/_i18n_merge.py values-xx path/to/frag.xml --drop name1 --drop name2
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RES = REPO_ROOT / "app" / "src" / "main" / "res"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("locale")
    parser.add_argument("fragment", type=Path)
    parser.add_argument("--drop", action="append", default=[])
    args = parser.parse_args()

    target = RES / args.locale / "strings.xml"
    text = target.read_text(encoding="utf-8")
    frag = args.fragment.read_text(encoding="utf-8")

    frag = frag.replace("<!-- APPEND -->", "").rstrip() + "\n"

    for name in args.drop:
        pattern = re.compile(
            rf'[ \t]*<(?:string|plurals)\s+name="{re.escape(name)}"[^>]*>.*?</(?:string|plurals)>\n?',
            re.DOTALL,
        )
        text, n = pattern.subn("", text)
        if n == 0:
            print(f"WARN: {args.locale}: no entry removed for {name}")
        else:
            print(f"{args.locale}: removed {n} entry/entries for {name}")

    idx = text.rfind("</resources>")
    if idx < 0:
        print(f"FATAL: {args.locale}: no </resources> found")
        return 1
    text = text[:idx] + frag + text[idx:]
    target.write_text(text, encoding="utf-8")
    print(f"{args.locale}: merged fragment ({len(frag.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())