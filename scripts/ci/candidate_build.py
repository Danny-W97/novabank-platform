"""Build a secret-free source export, including untracked additions, without changing Git state."""
from pathlib import Path
import subprocess
import tempfile
from source_bundle import ROOT, export


if __name__ == '__main__':
    logs = ROOT / 'target'
    logs.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='novabank-candidate-') as temp:
        candidate = Path(temp) / 'source'
        manifest = export(candidate)
        print(f'Candidate: {len(manifest)} public files; no runtime env, staging, commits or migrations.', flush=True)
        for module, options in [('bank', []), ('shop', ['-f', 'shop-app/pom.xml'])]:
            command = ['bash', './mvnw', '-o', '-B', '-ntp', '-l', str(logs/f'audit-candidate-{module}-build.log'),
                       *options, '-DskipTests', 'package']
            subprocess.run(command, cwd=candidate, check=True)
            print(f'Fresh exported {module} package: PASS', flush=True)
