#!/usr/bin/env python3
"""Exercise release metadata in temporary files; never builds or publishes."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from prepare_release import prepare_release


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.original = 'PACKAGE_NAME=com.isaiandco.neko\nVERSION_NAME=2.00\nPRE_VERSION_NAME=2.00\nVERSION_CODE=68\n'
        (self.root / 'nb4a.properties').write_text(self.original)

    def test_tag_controls_shared_build_properties_and_release_title(self):
        for tag in ('2.01', 'v2.01', 'v2.01-beta.1'):
            with self.subTest(tag=tag):
                (self.root / 'nb4a.properties').write_text(self.original)
                metadata = prepare_release(self.root, tag, '', 200, 'Исправлено восстановление VPN')
                properties = dict(line.split('=', 1) for line in (self.root / 'nb4a.properties').read_text().splitlines())
                version = tag.removeprefix('v')
                self.assertEqual(metadata['RELEASE_TAG'], tag)
                self.assertEqual(properties['VERSION_NAME'], version)
                self.assertEqual(properties['PRE_VERSION_NAME'], version)
                self.assertEqual(metadata['RELEASE_TITLE'], f'NekoBox_SF {version}')
                self.assertEqual(int(properties['VERSION_CODE']) * 5, 1340)
                notes = (self.root / 'release-notes.txt').read_text()
                self.assertIn(f'версии {version}', notes)
                self.assertIn('Исправлено восстановление VPN', notes)

    def test_old_release_notes_are_not_reused(self):
        notes = self.root / 'docs/releases/2.00.md'
        notes.parent.mkdir(parents=True)
        notes.write_text('OLD RELEASE NOTES')
        metadata = prepare_release(self.root, 'v2.02', 'Новое обновление', 201, 'Последний коммит\n\nПодробности')
        self.assertEqual(metadata['RELEASE_TITLE'], 'Новое обновление')
        text = (self.root / 'release-notes.txt').read_text()
        self.assertNotIn('OLD RELEASE NOTES', text)
        self.assertIn('Последний коммит\n\nПодробности', text)

    def test_empty_tag_generates_unique_app_version(self):
        metadata = prepare_release(self.root, '', '', 202, '')
        self.assertEqual(metadata['RELEASE_TAG'], 'v2.00-202')
        self.assertEqual(metadata['RELEASE_VERSION'], '2.00-202')
        self.assertIn('Описание изменений будет добавлено позже.', (self.root / 'release-notes.txt').read_text())

    def test_version_code_grows_on_next_run(self):
        codes = []
        for run in (200, 201):
            (self.root / 'nb4a.properties').write_text(self.original)
            prepare_release(self.root, f'v2.{run}', '', run, '')
            properties = dict(line.split('=', 1) for line in (self.root / 'nb4a.properties').read_text().splitlines())
            codes.append(int(properties['VERSION_CODE']))
        self.assertGreater(codes[1], codes[0])

    def test_bad_inputs_do_not_change_build_properties(self):
        for tag, title, run in [('v2.01\nINJECTED=true', '', 1), ('../2.01', '', 1), ('v2.01-beta..1', '', 1), ('v2.01', 'Title\nINJECTED=true', 1), ('v2.01', '', 0)]:
            with self.subTest(tag=tag, title=title, run=run):
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    prepare_release(self.root, tag, title, run, '')
                self.assertEqual((self.root / 'nb4a.properties').read_text(), self.original)

    def test_workflow_entry_point_reads_commit_and_exports_selected_tag(self):
        script = self.root / '.github/scripts/prepare_release.py'
        script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name('prepare_release.py'), script)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test',
                        'commit', '-q', '--allow-empty', '-m', 'Новое исправление из последнего коммита'], cwd=self.root, check=True)
        environment = self.root / 'github-env'
        env = dict(os.environ, INPUT_TAG='', INPUT_TITLE='', GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v2.03',
                   GITHUB_RUN_NUMBER='203', GITHUB_ENV=str(environment))
        subprocess.run(['python3', str(script)], env=env, check=True, capture_output=True)
        exported = dict(line.split('=', 1) for line in environment.read_text().splitlines())
        self.assertEqual(exported, {'RELEASE_TAG': 'v2.03', 'RELEASE_VERSION': '2.03', 'RELEASE_TITLE': 'NekoBox_SF 2.03'})
        self.assertIn('VERSION_NAME=2.03\n', (self.root / 'nb4a.properties').read_text())
        self.assertIn('Новое исправление из последнего коммита', (self.root / 'release-notes.txt').read_text())


if __name__ == '__main__':
    unittest.main()
