"""Export public candidate sources, including new files, without staging or copying local secrets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PREFIXES = ('src/', 'shop-app/src/', 'scripts/', 'infra/', '.github/', '.mvn/')
NAMES = {'pom.xml', 'shop-app/pom.xml', 'mvnw', 'mvnw.cmd', 'Dockerfile', 'docker-compose.yaml',
         '.dockerignore', '.gitignore', '.gitleaksignore', '.env.example', 'README.md', 'README.pl.md'}
BLOCKED = {'.novabank', '.git', '.codex', '.agents', 'node_modules', '__pycache__', 'target', '.venv'}


def export(destination, root=ROOT):
    root = root.resolve()
    destination = Path(destination).resolve()
    if destination.exists():
        raise ValueError('Source bundle destination must not exist.')
    names = subprocess.check_output(['git', '-c', 'core.fsmonitor=false', 'ls-files', '-z', '--cached',
                                     '--others', '--exclude-standard'], cwd=root).decode().split('\0')
    destination.mkdir(parents=True)
    manifest = []
    for name in sorted(set(names)):
        if not name or (name not in NAMES and not name.startswith(PREFIXES)):
            continue
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts or BLOCKED.intersection(relative.parts):
            continue
        source = root / relative
        if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(root):
            continue
        if BLOCKED.intersection(source.resolve().relative_to(root).parts):
            continue
        if source.suffix.lower() in ('.env', '.key', '.pem', '.jks', '.p12', '.dump', '.log'):
            continue
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        manifest.append({'file': name, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
    if not (destination / 'pom.xml').is_file() or not (destination / 'shop-app/pom.xml').is_file():
        raise ValueError('Incomplete candidate source inventory.')
    (destination / 'source-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('destination')
    args = parser.parse_args()
    print(f'Exported {len(export(args.destination))} candidate files; no Git state changed.')
