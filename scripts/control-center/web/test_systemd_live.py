"""Opt-in real systemd parser check; owns only UUID-named pwd units and fixtures."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import uuid

from operator_settings import regular

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(os.environ.get("NOVABANK_TEST_USER_SYSTEMD") == "1" and os.name != "nt", "opt-in user-systemd pwd probe")
class LiveWorkingDirectoryTests(unittest.TestCase):
    def test_real_systemd_interprets_both_renderers_spaces_and_percent(self):
        def run(args):
            return subprocess.run(args, check=True, capture_output=True, text=True, timeout=20).stdout
        with tempfile.TemporaryDirectory(prefix="novabank-systemd-pwd-") as directory:
            project = Path(directory) / "Bank Workspace 100%"
            for name in ("scripts/backup/backup.sh", "scripts/control-center/web/operator_settings.py", "scripts/control-center/web/dev_configuration.py", "scripts/control-center/web/dev_env_layout.py", "scripts/control-center/web/env_ownership.py"):
                target = project / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, target)
            fixture_env = project / "fixture.env"
            fixture_env.write_text("")
            # The same renderers also serve CLI, whose path grammar permits %.
            # WWW deliberately rejects % in backup paths before rendering.
            environment = dict(os.environ, NOVABANK_WEB_MODE="0", ENV_FILE=str(fixture_env))
            for command in ("schedule-install", "dr-schedule-install"):
                with self.subTest(command=command):
                    rendered = subprocess.run(["bash", str(project / "scripts/backup/backup.sh"), command, "--dry-run", "--backup-dir", str(project / "fixture-backups")], env=environment, check=True, capture_output=True, text=True, timeout=10).stdout
                    working = next(line for line in rendered.splitlines() if line.startswith("WorkingDirectory="))
                    unit = "novabank-pwd-check-" + uuid.uuid4().hex + ".service"
                    target = regular(Path.home() / ".config/systemd/user" / unit)
                    content = "[Service]\nType=oneshot\nRemainAfterExit=yes\n" + working + "\nExecStart=/usr/bin/pwd\n"
                    self.assertFalse(target.exists())
                    target.parent.mkdir(parents=True, exist_ok=True)
                    try:
                        with target.open("x") as stream:
                            stream.write(content)
                        target.chmod(0o600)
                        run(["systemctl", "--user", "daemon-reload"])
                        run(["systemctl", "--user", "start", unit])
                        found = json.loads(run(["busctl", "--user", "--json=short", "call", "org.freedesktop.systemd1", "/org/freedesktop/systemd1", "org.freedesktop.systemd1.Manager", "GetUnit", "s", unit]))["data"]
                        object_path = found[0] if isinstance(found, list) else found
                        actual = json.loads(run(["busctl", "--user", "--json=short", "get-property", "org.freedesktop.systemd1", object_path, "org.freedesktop.systemd1.Service", "WorkingDirectory"]))["data"]
                        actual = actual[0] if isinstance(actual, list) else actual
                        self.assertEqual(str(project), actual)
                        status = run(["systemctl", "--user", "show", unit, "--property=Result,ExecMainStatus", "--no-pager"])
                        self.assertIn("Result=success", status)
                        self.assertIn("ExecMainStatus=0", status)
                    finally:
                        # Never remove or stop a unit modified by another actor.
                        if target.exists() and regular(target).read_text() == content:
                            run(["systemctl", "--user", "stop", unit])
                            target.unlink()
                            run(["systemctl", "--user", "daemon-reload"])
