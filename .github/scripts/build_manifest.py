#!/usr/bin/env python3
"""Record source, toolchain, patch and actual APK signing information."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
def run(*args, cwd=root):
    return subprocess.check_output(args, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()

properties = dict(line.split('=', 1) for line in (root / 'nb4a.properties').read_text().splitlines() if '=' in line)
pins = dict(re.findall(r'export (COMMIT_\w+)="([0-9a-f]{40})"', (root / 'buildScript/lib/core/get_source_env.sh').read_text()))
patches = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted((root / 'buildScript/lib/core').glob('*.patch'))}
verification = json.loads(Path(sys.argv[2]).read_text())
public_cert = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(root / 'app/debug.keystore'), '-storepass', 'android', '-alias', 'androiddebugkey'], stderr=subprocess.DEVNULL)
certificate = verification['apks'][0]['certificate_sha256']
ndk = Path(os.environ['ANDROID_HOME']) / 'ndk/28.0.13004108/source.properties'
manifest = {
    'application_id': properties['PACKAGE_NAME'],
    'version_name': properties['VERSION_NAME'],
    'version_code': int(properties['VERSION_CODE']) * 5,
    'commit': os.environ.get('GITHUB_SHA') or run('git', 'rev-parse', 'HEAD'),
    'branch': os.environ.get('GITHUB_REF_NAME') or run('git', 'branch', '--show-current'),
    'go': run('go', 'version'),
    'java': run('java', '-version'),
    'ndk': ndk.read_text().strip(),
    'source_pins': pins,
    'bundled_route_assets': json.loads((root / 'buildScript/route-assets.json').read_text()),
    'core_patch_sha256': patches,
    'resolved_modules': run('go', 'list', '-m', 'all', cwd=root / 'libcore').splitlines(),
    'signing_certificate_sha256': certificate,
    'signing_key': 'public-development' if hashlib.sha256(public_cert).hexdigest() == certificate else 'private-ci',
    'apk_sha256': {entry['file']: entry['sha256'] for entry in verification['apks']},
}
Path(sys.argv[1]).write_text(json.dumps(manifest, indent=2) + '\n')
