#!/usr/bin/env python3
"""Syntax-check repository Python files without executing them.

Usage: uv run tools/diagnostics/py_syntax_check.py <file.py> [...]

Compiles each argument with py_compile (doraise) and reports the first error per file. Exit code 1 if any file fails.
"""

import py_compile
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    if len(sys.argv) < 2:
        print("usage: uv run tools/diagnostics/py_syntax_check.py <file.py> [...]")
        return 2
    failed = False
    for arg in sys.argv[1:]:
        path = Path(arg)
        if not path.is_absolute():
            path = ROOT / path
        try:
            py_compile.compile(str(path), doraise=True)
            print(f"OK {path.relative_to(ROOT)}")
        except py_compile.PyCompileError as e:
            print(f"FAIL {path}: {e.msg}")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())