"""Check that local release snapshots include reviewed changes without staging them."""

from pathlib import Path
import subprocess
import tempfile
import unittest

from prepare_local_release import snapshot_working_tree


class WorkingTreeSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='consentgate-snapshot-test-')
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.git('init', '--quiet')
        self.git('config', 'core.autocrlf', 'false')
        self.write('.gitignore', '.run/\nbuild/\n.env\n')
        self.write('modified.txt', 'original\n')
        self.write('deleted.txt', 'original\n')
        self.git('add', '--all')
        self.base_tree = self.git('write-tree').decode().strip()

    def git(self, *arguments):
        return subprocess.check_output(['git', *arguments], cwd=self.project)

    def write(self, name, content):
        path = self.project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8', newline='\n')

    def test_snapshot_includes_edits_additions_and_deletions_without_changing_index(self):
        self.write('modified.txt', 'staged\n')
        self.git('add', 'modified.txt')
        index_before = (self.project / '.git/index').read_bytes()
        self.write('modified.txt', 'reviewed\n')
        self.write('new.txt', 'new source\n')
        (self.project / 'deleted.txt').unlink()
        tree = snapshot_working_tree(self.project, self.base_tree)
        self.assertEqual(b'reviewed\n', self.git('show', tree + ':modified.txt'))
        self.assertEqual(b'new source\n', self.git('show', tree + ':new.txt'))
        self.assertNotIn(b'deleted.txt', self.git('ls-tree', '-r', '--name-only', tree))
        self.assertEqual(index_before, (self.project / '.git/index').read_bytes())

    def test_ignored_local_files_are_excluded_but_tracked_ignored_files_survive(self):
        self.write('.run/private.txt', 'private fixture\n')
        self.write('.env', 'PRIVATE_FIXTURE=true\n')
        self.write('build/generated.txt', 'generated\n')
        self.write('build/tracked.txt', 'tracked fixture\n')
        self.git('add', '--force', 'build/tracked.txt')
        base_tree = self.git('write-tree').decode().strip()
        tree = snapshot_working_tree(self.project, base_tree)
        names = self.git('ls-tree', '-r', '--name-only', tree).decode().splitlines()
        self.assertIn('build/tracked.txt', names)
        self.assertNotIn('build/generated.txt', names)
        self.assertNotIn('.run/private.txt', names)
        self.assertNotIn('.env', names)

    def test_source_changes_produce_a_new_tree(self):
        before = snapshot_working_tree(self.project, self.base_tree)
        self.write('modified.txt', 'changed during build\n')
        after = snapshot_working_tree(self.project, self.base_tree)
        self.assertNotEqual(before, after)
        self.assertEqual(after, snapshot_working_tree(self.project, self.base_tree))


if __name__ == '__main__':
    unittest.main()
