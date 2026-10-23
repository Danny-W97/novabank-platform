"""Readiness for already-owned disposable PostgreSQL fixtures, never Compose/DEV."""
import subprocess
import time


def wait_for_postgres(docker, container, *, timeout=30):
    # The image entrypoint briefly starts a socket-only server while initializing
    # the cluster, then stops it. TCP becomes available only on the final server.
    deadline = time.monotonic() + timeout
    while True:
        ready = subprocess.run(docker + ['exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                               capture_output=True, timeout=5)
        if ready.returncode == 0:
            return
        if time.monotonic() >= deadline:
            raise AssertionError('Owned PostgreSQL fixture TCP readiness timeout')
        time.sleep(.2)
