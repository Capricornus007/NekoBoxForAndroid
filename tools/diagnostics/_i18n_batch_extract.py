"""Temporary helper: extract missing/extra i18n entries for a batch of locales.

Read-only. Outputs one file per locale under tmp_i18n/ with:
  - missing strings (name + base text)
  - missing plurals (name + base items)
  - missing string-arrays (name + base items)
  - extra entry names (present in locale but not in base)
  - non-translatable leaks, checked per entry type

Run from repo root:
    uv run tools/diagnostics/_i18n_batch_extract.py values-xx ...
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RES = REPO_ROOT / "app" / "src" / "main" / "res"
OUT = REPO_ROOT / "tmp_i18n"


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def is_translatable(el: ET.Element) -> bool:
    return el.get("translatable", "true").lower() != "false"


def text_of(el: ET.Element) -> str:
    return "".join(el.itertext())


def collect(root: ET.Element):
    strings, plurals, arrays = {}, {}, {}
    for el in root:
        name = el.get("name")
        if not name:
            continue
        kind = local_name(el.tag)
        if kind == "string":
            strings[name] = el
        elif kind == "plurals":
            plurals[name] = el
        elif kind == "string-array":
            arrays[name] = el
    return strings, plurals, arrays


def main() -> int:
    locales = sys.argv[1:]
    if not locales:
        print("usage: _i18n_batch_extract.py values-xx ...")
        return 2

    b_s_root = ET.parse(RES / "values" / "strings.xml").getroot()
    b_a_root = ET.parse(RES / "values" / "arrays.xml").getroot()
    b_strings, b_plurals, _ = collect(b_s_root)
    _, _, b_arrays = collect(b_a_root)

    base_string_names = {n for n, el in b_strings.items() if is_translatable(el)}
    base_plural_names = {n for n, el in b_plurals.items() if is_translatable(el)}
    base_array_names = {n for n, el in b_arrays.items() if is_translatable(el)}
    nt_strings = {
        n for n, el in list(b_strings.items()) + list(b_plurals.items())
        if not is_translatable(el)
    }
    nt_arrays = {n for n, el in b_arrays.items() if not is_translatable(el)}

    OUT.mkdir(exist_ok=True)
    for loc in locales:
        s_root = ET.parse(RES / loc / "strings.xml").getroot()
        a_path = RES / loc / "arrays.xml"
        a_root = ET.parse(a_path).getroot() if a_path.exists() else None
        l_strings, l_plurals, _ = collect(s_root)
        _, _, l_arrays = collect(a_root) if a_root is not None else ({}, {}, {})

        present = set(l_strings) | set(l_plurals)
        lines: list[str] = []

        miss_s = sorted(base_string_names - present)
        lines.append(f"## missing strings: {len(miss_s)}")
        for n in miss_s:
            lines.append(f"### string {n}")
            lines.append(text_of(b_strings[n]))

        miss_p = sorted(base_plural_names - set(l_plurals))
        lines.append(f"## missing plurals: {len(miss_p)}")
        for n in miss_p:
            lines.append(f"### plurals {n}")
            for item in b_plurals[n]:
                if local_name(item.tag) == "item":
                    lines.append(f"{item.get('quantity')}: {text_of(item)}")

        miss_a = sorted(base_array_names - set(l_arrays))
        lines.append(f"## missing arrays: {len(miss_a)}")
        for n in miss_a:
            lines.append(f"### array {n}")
            for item in b_arrays[n]:
                if local_name(item.tag) == "item":
                    lines.append(text_of(item))

        str_all = set(l_strings) | set(l_plurals)
        extra_s = sorted((str_all - base_string_names - base_plural_names) - nt_strings)
        leak_s = sorted(str_all & nt_strings)
        extra_a = sorted(set(l_arrays) - base_array_names - nt_arrays)
        leak_a = sorted(set(l_arrays) & nt_arrays)
        lines.append(f"## extra string names: {len(extra_s)}")
        lines.extend(extra_s)
        lines.append(f"## extra array names: {len(extra_a)}")
        lines.extend(extra_a)
        lines.append(f"## nontranslatable leaks (strings): {len(leak_s)}")
        lines.extend(leak_s)
        lines.append(f"## nontranslatable leaks (arrays): {len(leak_a)}")
        lines.extend(leak_a)

        out = OUT / f"{loc}.missing.txt"
        out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{out.relative_to(REPO_ROOT)}: miss_s={len(miss_s)} miss_p={len(miss_p)} "
              f"miss_a={len(miss_a)} extra_s={len(extra_s)} extra_a={len(extra_a)} "
              f"leak_s={len(leak_s)} leak_a={len(leak_a)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())