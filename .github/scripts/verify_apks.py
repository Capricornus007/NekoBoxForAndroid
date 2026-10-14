#!/usr/bin/env python3
"""Verify the actual release APKs, including native 16 KiB page compatibility."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[2]
properties = dict(line.split('=', 1) for line in (root / 'nb4a.properties').read_text().splitlines() if '=' in line)
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.1'
directory = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'app/build/outputs/apk/oss/release'
abis = {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}
apks = sorted(directory.glob('*.apk'))
assert len(apks) == 4, f'Expected four ABI APKs, found {len(apks)}'
seen, signers, report = set(), set(), []

def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, stderr=subprocess.STDOUT)

for apk in apks:
    badging = run(tools / 'aapt2', 'dump', 'badging', apk)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    assert package and package[1] == properties['PACKAGE_NAME'], f'Wrong package: {apk}'
    assert int(package[2]) == int(properties['VERSION_CODE']) * 5, f'Wrong version code: {apk}'
    assert package[3] == properties['VERSION_NAME'], f'Wrong version name: {apk}'
    assert 'application-debuggable' not in badging, f'Debuggable APK: {apk}'
    verification = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', verification)
    assert signer, f'Missing signer: {apk}'
    signers.add(signer[1])
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
        actual_abis = {name.split('/')[1] for name in libraries}
        assert len(actual_abis) == 1 and actual_abis <= abis, f'Wrong ABI contents: {apk}'
        abi = next(iter(actual_abis))
        assert abi not in seen, f'Duplicate ABI: {abi}'
        seen.add(abi)
        for name in libraries:
            data = archive.read(name)
            assert data[:4] == b'\x7fELF', f'Not ELF: {name}'
            if abi not in {'arm64-v8a', 'x86_64'}:
                assert data[4] == 1, f'Wrong ELF class: {name}'
                continue
            assert data[4:6] == b'\x02\x01', f'Expected ELF64 little endian: {name}'
            header = struct.unpack_from('<HHIQQQIHHHHHH', data, 16)
            phoff, phentsize, phnum = header[4], header[8], header[9]
            loads = 0
            for index in range(phnum):
                kind, flags, offset, address, physical, filesz, memsz, alignment = struct.unpack_from('<IIQQQQQQ', data, phoff + index * phentsize)
                if kind == 1:
                    loads += 1
                    assert alignment >= 16384 and offset % 16384 == address % 16384, f'Unaligned LOAD: {name}'
                if kind == 0x6474e552:
                    assert (address + memsz) % 16384 == 0, f'Unaligned RELRO end: {name}'
            assert loads, f'No LOAD segments: {name}'
    report.append({'file': apk.name, 'abi': abi, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'certificate_sha256': signer[1]})
assert seen == abis and len(signers) == 1, 'Incomplete ABI coverage or inconsistent signatures'
result = {'application_id': properties['PACKAGE_NAME'], 'debuggable': False, 'native_64_bit_page_size': 16384, 'apks': report}
if len(sys.argv) > 2:
    Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
