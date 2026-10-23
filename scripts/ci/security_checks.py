"""Pinned scanners on a public source bundle and a private temporary Git mirror; no runtime env mounts."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
from source_bundle import ROOT, export

GITLEAKS = 'ghcr.io/gitleaks/gitleaks:v8.30.1'
OSV = 'ghcr.io/google/osv-scanner:v2.7.0'


def container(image, mounts, command, network=True):
    args = ['docker', 'run', '--rm']
    # Linux bind mounts preserve the creating UID. Root-owned scanner output
    # cannot be sanitized by the unprivileged GitHub runner afterwards.
    if os.name == 'posix':
        args += ['--user', f'{os.getuid()}:{os.getgid()}']
    if not network:
        args += ['--network=none']
    for source, target, writable in mounts:
        args += ['-v', f'{source.resolve()}:{target}:{"rw" if writable else "ro"}']
    args += ['-e', 'HOME=/tmp', '-e', 'XDG_CACHE_HOME=/tmp/.cache',
             '-e', 'GIT_CONFIG_COUNT=1', '-e', 'GIT_CONFIG_KEY_0=safe.directory',
             '-e', 'GIT_CONFIG_VALUE_0=/history', image, *command]
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900).returncode


def sanitize(path):
    if not path.is_file():
        raise ValueError('Scanner did not produce its required secret report.')
    findings = json.loads(path.read_text(encoding='utf8'))
    if not isinstance(findings, list) or any(not isinstance(row, dict) for row in findings):
        raise ValueError('Scanner returned an invalid secret report.')
    safe = [{key: row.get(key) for key in ('RuleID', 'File', 'StartLine', 'Commit', 'Fingerprint')} for row in findings]
    path.write_text(json.dumps(safe, indent=2)+'\n', encoding='utf8')
    return len(safe)


def publish_summary(reports, mode, results, failed):
    """Preserve the verdict in logs/Actions summary even when artifact quota is full."""
    verdict = 'FAIL' if failed else 'PASS'
    report = {'mode': mode, 'verdict': verdict, 'checks': results}
    (reports / f'{mode}-summary.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf8')
    print(json.dumps(report, indent=2), flush=True)
    summary_path = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary_path:
        with open(summary_path, 'a', encoding='utf8') as summary:
            summary.write(f'### Security scan: {mode} — {verdict}\n\n')
            summary.write('| Check | Exit code | Findings |\n| --- | ---: | ---: |\n')
            for result in results:
                summary.write(f"| {result['scan']} | {result['exitCode']} | {result.get('findings', 'See scanner verdict')} |\n")
            summary.write('\nScanner errors and findings remain blocking. Artifact upload is separate.\n\n')


def main(mode):
    reports = ROOT / 'target/security'
    reports.mkdir(parents=True, exist_ok=True)
    failed = False
    results = []
    with tempfile.TemporaryDirectory(prefix='novabank-security-') as directory:
        scratch = Path(directory)
        source = scratch / 'source'
        manifest = export(source)
        (reports / 'source-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        if mode == 'secrets':
            fixture = scratch / 'canary'; fixture.mkdir()
            canary = secrets.token_hex(32)
            (fixture / 'fixture.py').write_text('api_key = "'+canary+'"\n')
            common = ['--redact=100', '--no-banner', '--report-format=json']
            code = container(GITLEAKS, [(fixture, '/source', False), (reports, '/reports', True)],
                             ['dir', *common, '--report-path=/reports/canary.json', '/source'], network=False)
            if code != 1 or sanitize(reports / 'canary.json') == 0:
                raise RuntimeError('Secret scanner canary did not fail as expected.')
            history = scratch / 'history.git'
            subprocess.run(['git', '-c', 'core.fsmonitor=false', 'clone', '--mirror', '--no-hardlinks', str(ROOT), str(history)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            for label, mount, command in [('history', history, ['git', '--log-opts=--all', '/history']),
                                           ('candidate', source, ['dir', '/source'])]:
                target = '/history' if label == 'history' else '/source'
                code = container(GITLEAKS, [(mount, target, False), (reports, '/reports', True), (source, '/rules', False)],
                                 [command[0], *common, '--gitleaks-ignore-path=/rules/.gitleaksignore',
                                  '--report-path=/reports/'+label+'.json', *command[1:]], network=False)
                count = sanitize(reports / (label+'.json'))
                results.append({'scan': label, 'exitCode': code, 'findings': count})
                print(f'Gitleaks {label}: exit={code}, findings={count}; values omitted.')
                failed |= code != 0 or count != 0
        else:
            code = container(OSV, [(source, '/source', False), (reports, '/reports', True)],
                             ['scan', 'source', '--recursive', '--format=json', '--output=/reports/dependencies.json', '/source'])
            print(f'OSV dependency scan: exit={code}; report in target/security/dependencies.json.')
            failed |= code != 0
            results.append({'scan': 'dependencies', 'exitCode': code})
    publish_summary(reports, mode, results, failed)
    return int(failed)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('secrets', 'dependencies'))
    args = parser.parse_args()
    raise SystemExit(main(args.mode))
