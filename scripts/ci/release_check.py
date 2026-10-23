"""Read-only release gate: never stage, commit, push or copy runtime configuration."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
from source_bundle import ROOT, export, NAMES, PREFIXES, BLOCKED


def check(root=ROOT, revision='HEAD'):
    root=Path(root).resolve()
    def git(*args):
        return subprocess.check_output(['git','-c','core.fsmonitor=false',*args],cwd=root,stderr=subprocess.PIPE)
    commit=git('rev-parse','--verify',revision+'^{commit}').decode().strip()
    with tempfile.TemporaryDirectory(prefix='novabank-release-inventory-') as directory:
        candidate=Path(directory)/'source'
        manifest=export(candidate,root)
        committed=set(git('ls-tree','-r','--name-only',commit).decode().splitlines())
        missing=[];changed=[]
        for row in manifest:
            name=row['file']
            if name not in committed:missing.append(name);continue
            # Apply Git's clean/EOL rules, including Windows CRLF checkouts.
            expected=git('rev-parse',commit+':'+name).strip()
            actual=git('hash-object','--path='+name,str(candidate/name)).strip()
            if expected!=actual:changed.append(name)
        # A tracked deletion must not disappear from the candidate inventory.
        deletions=git('diff','--name-only','--diff-filter=D',commit).decode().splitlines()
        deleted=[name for name in deletions if (name in NAMES or name.startswith(PREFIXES)) and not BLOCKED.intersection(Path(name).parts)]
    ready=not(missing or changed or deleted)
    return {'verdict':'READY_FOR_CLEAN_CHECKOUT_CI' if ready else 'NOT_READY_UNCOMMITTED_SOURCE',
            'commit':commit,'publicFiles':len(manifest),'missingFromCommit':missing,'changedSinceCommit':changed,
            'deletedSinceCommit':deleted,'ciExecuted':False,'workingTreeModified':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--revision',default='HEAD');args=parser.parse_args()
    try:
        result=check(revision=args.revision)
        print(json.dumps(result,ensure_ascii=False,indent=2))
        raise SystemExit(0 if result['verdict']=='READY_FOR_CLEAN_CHECKOUT_CI' else 2)
    except (OSError,subprocess.SubprocessError,ValueError):
        print('Release gate unavailable: verify the repository and commit; no Git state changed.')
        raise SystemExit(1)
