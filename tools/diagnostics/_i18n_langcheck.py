"""Temporary helper: scan locale strings.xml for characters of unexpected scripts.

- values-ja: Hangul is unexpected
- values-ko: Kana is unexpected
- values-ru / values-uk: CJK / Kana / Hangul unexpected (language name rows exempt)
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res"

HANGUL = re.compile(r"[가-힯]")
KANA = re.compile(r"[぀-ヿ]")
CJK = re.compile(r"[一-鿿]")

EXEMPT = re.compile(r'name="(language_entries|language_values)"')


def scan(path: Path, patterns: list[re.Pattern[str]]) -> int:
    hits = 0
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if EXEMPT.search(line):
            continue
        for pat in patterns:
            m = pat.search(line)
            if m:
                hits += 1
                print(f"{path.parent.name}:{i}: {m.group(0)!r} :: {line.strip()[:90]}")
                break
    return hits


def main() -> int:
    total = 0
    total += scan(RES / "values-ja" / "strings.xml", [HANGUL])
    total += scan(RES / "values-ko" / "strings.xml", [KANA])
    total += scan(RES / "values-ru" / "strings.xml", [CJK, KANA, HANGUL])
    total += scan(RES / "values-uk" / "strings.xml", [CJK, KANA, HANGUL])
    print(f"total suspicious lines: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())