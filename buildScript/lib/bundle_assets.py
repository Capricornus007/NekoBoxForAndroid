#!/usr/bin/env python3
"""Bundle pinned, checksum-verified route databases; optionally use a local cache."""
import hashlib
import json
import lzma
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
pins = json.loads((root / 'buildScript/route-assets.json').read_text())
destination = root / 'app/src/main/assets/sing-box'
destination.mkdir(parents=True, exist_ok=True)
cache = Path(sys.argv[1]) if len(sys.argv) > 1 else None
with tempfile.TemporaryDirectory(dir=destination) as temporary:
    stage = Path(temporary)
    for name, pin in pins.items():
        database = stage / f'{name}.db'
        if cache:
            database.write_bytes((cache / database.name).read_bytes())
        else:
            url = f'https://github.com/{pin["repository"]}/releases/download/{pin["version"]}/{name}.db'
            subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--retry', '3', '--max-time', '300', '--max-filesize', '536870912', '--proto', '=https', '--proto-redir', '=https', '--output', str(database), url], check=True)
        contents = database.read_bytes()
        if hashlib.sha256(contents).hexdigest() != pin['sha256']:
            raise SystemExit(f'{name}: checksum mismatch')
        packed = stage / f'{name}.db.xz'
        packed.write_bytes(lzma.compress(contents, preset=9))
        version = stage / f'{name}.version.txt'
        version.write_text(pin['version'])
        packed.replace(destination / packed.name)
        version.replace(destination / version.name)
        print(f'Bundled {name} {pin["version"]}: {len(contents)} bytes, SHA-256 verified')
