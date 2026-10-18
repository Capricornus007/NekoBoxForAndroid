#!/usr/bin/env python3
"""Prepare one release identity before Gradle, APK verification and publishing."""
import os
from pathlib import Path
import re
import subprocess


def prepare_release(root, tag, title, run_number, commit_message):
    properties_path = root / 'nb4a.properties'
    source = properties_path.read_text()
    properties = dict(line.split('=', 1) for line in source.splitlines() if '=' in line)
    run_number = int(run_number)
    if run_number < 1:
        raise ValueError('Workflow run number must be positive')
    tag = tag.strip() or f"v{properties['VERSION_NAME']}-{run_number}"
    if not re.fullmatch(r'v?\d+(?:\.\d+)+(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?', tag):
        raise ValueError('Use a version tag such as 2.01, v2.01 or v2.01-beta.1')
    subprocess.run(['git', 'check-ref-format', f'refs/tags/{tag}'], check=True)
    version = tag.removeprefix('v')
    title = title.strip() or f'NekoBox_SF {version}'
    if any(char in title for char in '\r\n\0'):
        raise ValueError('Release title must be one line')
    # Gradle multiplies this property by five. Each new workflow run must
    # produce an installable update even when nb4a.properties is unchanged.
    version_code = int(properties['VERSION_CODE']) + run_number
    if not 0 < version_code * 5 <= 2_100_000_000:
        raise ValueError('Android version code is out of range')
    for key, value in {'VERSION_NAME': version, 'PRE_VERSION_NAME': version, 'VERSION_CODE': str(version_code)}.items():
        source, count = re.subn(rf'^{key}=.*$', f'{key}={value}', source, flags=re.M)
        if count != 1:
            raise ValueError(f'Expected exactly one {key} property')
    notes = commit_message.strip() or 'Описание изменений будет добавлено позже.'
    (root / 'release-notes.txt').write_text(f'Изменения в версии {version}:\n\n{notes}\n')
    properties_path.write_text(source)
    return {'RELEASE_TAG': tag, 'RELEASE_VERSION': version, 'RELEASE_TITLE': title}


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[2]
    tag = os.environ.get('INPUT_TAG', '').strip()
    if not tag and os.environ.get('GITHUB_REF_TYPE') == 'tag':
        tag = os.environ['GITHUB_REF_NAME']
    message = subprocess.check_output(['git', 'log', '-1', '--format=%B'], cwd=root, text=True)
    metadata = prepare_release(root, tag, os.environ.get('INPUT_TITLE', ''), os.environ['GITHUB_RUN_NUMBER'], message)
    with open(os.environ['GITHUB_ENV'], 'a') as output:
        for key, value in metadata.items():
            output.write(f'{key}={value}\n')
    print(f"Release {metadata['RELEASE_TAG']}; app version {metadata['RELEASE_VERSION']}")
