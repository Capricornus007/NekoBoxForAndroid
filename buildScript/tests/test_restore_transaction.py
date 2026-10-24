#!/usr/bin/env python3
"""Exercise SQLite's two-file restore with rollback and process interruption."""
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

class RestoreTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        for name, table in [('profiles', 'profiles'), ('settings', 'KeyValuePair')]:
            with sqlite3.connect(self.directory / name) as db:
                db.execute('PRAGMA journal_mode=TRUNCATE')
                db.execute(f'CREATE TABLE {table} (value TEXT UNIQUE)')
                db.execute(f'INSERT INTO {table} VALUES (?)', ('original',))

    def tearDown(self):
        self.temporary.cleanup()

    def values(self):
        result = []
        for name, table in [('profiles', 'profiles'), ('settings', 'KeyValuePair')]:
            with sqlite3.connect(self.directory / name) as db:
                result.append(db.execute(f'SELECT value FROM {table}').fetchall())
        return result

    def restore(self, fail=False):
        with sqlite3.connect(self.directory / 'profiles') as db:
            db.execute('ATTACH DATABASE ? AS restore_settings', (str(self.directory / 'settings'),))
            for schema in ['main', 'restore_settings']:
                self.assertIn(db.execute(f'PRAGMA {schema}.journal_mode').fetchone()[0], {'delete', 'truncate', 'persist'})
                db.execute(f'PRAGMA {schema}.synchronous=FULL')
            db.execute('DELETE FROM profiles')
            db.execute("INSERT INTO profiles VALUES ('replacement')")
            db.execute('DELETE FROM restore_settings.KeyValuePair')
            db.execute("INSERT INTO restore_settings.KeyValuePair VALUES ('replacement')")
            if fail:
                db.execute("INSERT INTO restore_settings.KeyValuePair VALUES ('replacement')")

    def test_success(self):
        self.restore()
        self.assertEqual([[('replacement',)], [('replacement',)]], self.values())

    def test_settings_failure_rolls_back_profiles(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.restore(fail=True)
        self.assertEqual([[('original',)], [('original',)]], self.values())

    def test_process_exit_before_commit_keeps_both_originals(self):
        script = '''import sqlite3,sys,os
from pathlib import Path
p=Path(sys.argv[1]); db=sqlite3.connect(p/'profiles')
db.execute('ATTACH DATABASE ? AS restore_settings',(str(p/'settings'),))
for schema in ['main','restore_settings']: db.execute(f'PRAGMA {schema}.synchronous=FULL')
db.execute('DELETE FROM profiles'); db.execute("INSERT INTO profiles VALUES ('replacement')")
db.execute('DELETE FROM restore_settings.KeyValuePair'); db.execute("INSERT INTO restore_settings.KeyValuePair VALUES ('replacement')")
os._exit(73)
'''
        result = subprocess.run([sys.executable, '-c', script, str(self.directory)])
        self.assertEqual(73, result.returncode)
        self.assertEqual([[('original',)], [('original',)]], self.values())

if __name__ == '__main__':
    unittest.main()
