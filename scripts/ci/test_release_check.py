from pathlib import Path
import subprocess
import tempfile
import unittest
from release_check import check


class ReleaseCheckTests(unittest.TestCase):
    def test_untracked_modified_and_deleted_sources_cannot_be_released(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);subprocess.run(['git','init','-q',str(root)],check=True)
            def git(*args):return subprocess.check_output(['git','-c','user.name=Synthetic','-c','user.email=fixture@example.invalid',*args],cwd=root)
            for name in ('pom.xml','shop-app/pom.xml','src/main/java/Original.java','Dockerfile'):
                file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text('fixture')
            git('add','pom.xml','shop-app/pom.xml','src','Dockerfile');git('commit','-qm','Synthetic release fixture')
            self.assertEqual('READY_FOR_CLEAN_CHECKOUT_CI',check(root)['verdict'])
            (root/'src/main/java/New.java').write_text('new fixture')
            (root/'pom.xml').write_text('changed fixture')
            (root/'src/main/java/Original.java').unlink()
            (root/'Dockerfile').unlink()
            before=git('status','--porcelain')
            report=check(root)
            self.assertEqual(['src/main/java/New.java'],report['missingFromCommit'])
            self.assertIn('pom.xml',report['changedSinceCommit'])
            self.assertIn('src/main/java/Original.java',report['deletedSinceCommit'])
            self.assertIn('Dockerfile',report['deletedSinceCommit'])
            self.assertFalse(report['ciExecuted']);self.assertEqual(before,git('status','--porcelain'))


if __name__=='__main__':unittest.main()
