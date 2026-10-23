import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from source_bundle import export


class SourceBundleTest(unittest.TestCase):
    def test_includes_new_sources_but_never_runtime_secrets_or_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'repo'
            root.mkdir()
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            files = {'pom.xml': 'bank', 'shop-app/pom.xml': 'shop',
                     'src/main/java/New.java': 'candidate', '.novabank/env/dev.env': 'private-fixture',
                     'src/main/private.key': 'private-fixture', 'scripts/target/private': 'artifact',
                     'scripts/node_modules/private/index.js': 'dependency',
                     'scripts/private.log': 'private-fixture'}
            for name, value in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value)
            # A directory symlink must not bypass the resolved-path secret exclusion.
            try:
                (root / 'scripts/linked-runtime').symlink_to(root / '.novabank', target_is_directory=True)
            except OSError:
                pass
            before = subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)
            target = Path(temp) / 'export'
            manifest = export(target, root)
            self.assertEqual({'pom.xml', 'shop-app/pom.xml', 'src/main/java/New.java'},
                             {item['file'] for item in manifest})
            for item in json.loads((target / 'source-manifest.json').read_text()):
                self.assertEqual(item['sha256'], hashlib.sha256((target / item['file']).read_bytes()).hexdigest())
            self.assertEqual(before, subprocess.check_output(['git', 'status', '--porcelain'], cwd=root))
            with self.assertRaises(ValueError):
                export(target, root)


if __name__ == '__main__':
    unittest.main()
