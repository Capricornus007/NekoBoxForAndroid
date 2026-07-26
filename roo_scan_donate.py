import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__))
PATTERNS = [re.compile(r"donate", re.IGNORECASE), re.compile(r"card_giftcard", re.IGNORECASE)]
SKIP_DIRS = {".git", "build", ".gradle", ".idea"}

for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith("build")]
    for fn in filenames:
        path = os.path.join(dirpath, fn)
        rel = os.path.relpath(path, ROOT)
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                for i, line in enumerate(f, 1):
                    if any(p.search(line) for p in PATTERNS):
                        print(f"{rel}:{i}: {line.rstrip()[:200]}")
        except Exception as e:
            print(f"[skip] {rel}: {e}")
