"""Run every Control Center JavaScript regression, failing on the first nonzero exit."""
from pathlib import Path
import subprocess


if __name__ == '__main__':
    tests = sorted((Path(__file__).resolve().parents[1]/'control-center/web').glob('test_*.js'))
    if not tests:
        raise RuntimeError('JavaScript test inventory is empty.')
    for test in tests:
        subprocess.run(['node', str(test)], check=True)
        print(f'PASS {test.name}', flush=True)
    print(f'JavaScript suites: {len(tests)} PASS')
