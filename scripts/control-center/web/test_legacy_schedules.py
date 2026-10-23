"""Fake systemd and disposable unit files; never touches real user timers."""
import os
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from legacy_schedules import LegacySchedules, KINDS
from operator_settings import OperatorSettings
from dev_configuration import ConfigurationConflict


class FakeSystemd:
    def __init__(self, root, units):
        self.root, self.units = root, units
        self.states = {}
        self.calls = []
        self.fail = ""

    def service(self, name, operation, marker=""):
        text = self.service_text(operation, marker)
        (self.units / (name + ".service")).write_text(text)
        (self.units / (name + ".timer")).write_text("[Timer]\nOnCalendar=*-*-* 02:00:00\nUnit=" + name + ".service\n" + marker)
        self.states[name + ".timer"] = {"active": True, "enabled": True}

    def service_text(self, operation, marker=""):
        return "[Service]\nWorkingDirectory=" + str(self.root) + "\nExecStart=" + " ".join(shlex.quote(str(x)) for x in ("/usr/bin/bash", self.root / "scripts/backup/backup.sh", operation, "--backup-dir", self.root / ".novabank/backups/dev")) + "\n" + marker

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if args[0] == "bash":
            name = "novabank-web-dev-backup" if args[-2] == "backup-schedule-install" else "novabank-web-dev-dr-test"
            ident = kwargs["env"].get("NOVABANK_WEB_MIGRATION_ID", "")
            marker = "# NovaBank migration " + ident + "\n" if ident else ""
            text = f"--- {self.units / (name + '.service')} ---\n" + self.service_text("prune" if name.endswith("backup") else "dr-test", marker)
            text += f"--- {self.units / (name + '.timer')} ---\n[Timer]\nUnit={name}.service\n" + marker
            return subprocess.CompletedProcess(args, 1 if self.fail == "before-write" else 0, text, "private-systemd-stderr")
        if args[0] == "busctl":
            if "GetUnit" in args:
                data = ["/org/freedesktop/systemd1/unit/" + args[-1].replace("-", "_2d").replace(".", "_2e")]
            else:
                name = args[-3].rsplit("/", 1)[-1].replace("_2d", "-").replace("_2e", ".")
                text = (self.units / name).read_text()
                data = self.states.get(name, {}).get("workingDirectory", next((line.partition("=")[2] for line in text.splitlines() if line.startswith("WorkingDirectory=")), ""))
            return subprocess.CompletedProcess(args, 0, json.dumps({"data": data}), "")
        operation = args[2]
        if operation == "show":
            name = args[3]
            exists = (self.units / name).exists()
            state = self.states.get(name, {})
            text = (f"LoadState={'loaded' if exists else 'not-found'}\nActiveState={'active' if state.get('active') else 'inactive'}\n"
                    f"UnitFileState={'enabled' if state.get('enabled') else 'disabled' if exists else ''}\nFragmentPath={self.units / name if exists else ''}\nDropInPaths=\nTriggers={name.removesuffix('.timer')+'.service' if name.endswith('.timer') else ''}\n"
                    f"Result={state.get('result','success')}\nExecMainCode={state.get('code','0')}\nExecMainStatus={state.get('exit','0')}\nExecMainStartTimestamp={state.get('started','')}\nExecMainExitTimestamp={state.get('finished','')}\n")
            return subprocess.CompletedProcess(args, 0, text, "")
        if operation in ("disable", "enable", "start", "stop"):
            state = self.states.setdefault(args[-1], {})
            if operation == "disable": state.update(active=False, enabled=False)
            if operation == "enable": state["enabled"] = True
            if operation == "enable" and "--now" in args: state["active"] = True
            if operation == "start": state["active"] = True
            if operation == "stop": state["active"] = False
            if operation == "enable" and args[-1].startswith("novabank-web-") and self.fail == "after-write":
                return subprocess.CompletedProcess(args, 1, "", "private failure")
        return subprocess.CompletedProcess(args, 0, "", "")


@unittest.skipIf(os.name == "nt", "systemd fixtures run in WSL/Linux")
class LegacyScheduleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "project with space"
        self.root.mkdir()
        self.units = Path(self.temp.name) / "units"
        self.units.mkdir()
        self.settings = OperatorSettings(self.root)
        self.runner = FakeSystemd(self.root, self.units)
        self.runner.service("novabank-backup", "prune")
        self.manager = LegacySchedules(self.root, self.settings, "fixture-key", self.units, self.runner)

    def tearDown(self):
        self.temp.cleanup()

    def mutations(self):
        return [args for args, _ in self.runner.calls if args[0] != "busctl" and (args[0] == "bash" or args[2] != "show")]

    def test_plan_only_reads_and_blocks_duplicate_install(self):
        plan = self.manager.plan("backup")
        self.assertTrue(plan["canMigrate"])
        self.assertEqual([], self.mutations())
        with self.assertRaises(ValueError):
            self.manager.guard_install("backup")
        self.assertEqual([], self.mutations())

    def test_migration_keeps_cli_files_and_installs_only_this_project_new_timer(self):
        before = (self.units / "novabank-backup.service").read_bytes()
        plan = self.manager.plan("backup")
        output = []
        self.manager.migrate("backup", plan["revision"], {"PATH": "/fixture"}, output.append)
        self.assertEqual(before, (self.units / "novabank-backup.service").read_bytes())
        self.assertEqual({"active": False, "enabled": False}, self.runner.states["novabank-backup.timer"])
        self.assertTrue(self.runner.states["novabank-web-dev-backup.timer"]["active"])
        self.assertIn("Migracja zakończona", "".join(output))
        self.assertEqual(1, len(list((self.root / ".novabank/control-center/web/timer-migrations").glob("*/plan.json"))))
        installer = next((args, kw) for args, kw in self.runner.calls if args[0] == "bash")
        self.assertEqual(["backup-schedule-install", "dev"], installer[0][-2:])
        self.assertEqual("30", installer[1]["env"]["NOVABANK_WEB_BACKUP_RETENTION_DAYS"])

    def test_stale_plan_foreign_units_and_running_service_never_mutate(self):
        plan = self.manager.plan("backup")
        state = self.settings.state("dev")
        self.settings.save("dev", state["revision"], state["values"] | {"backupAt": "22:12"}, True)
        with self.assertRaises(ConfigurationConflict):
            self.manager.migrate("backup", plan["revision"], {}, lambda line: None)
        self.runner.states["novabank-backup.service"] = {"active": True}
        self.assertFalse(self.manager.plan("backup")["canMigrate"])
        self.runner.states.clear()
        (self.units / "novabank-backup.service").write_text('[Service]\nExecStart=/usr/bin/bash /another/project/scripts/backup/backup.sh prune --backup-dir /tmp/fixture\n')
        with self.assertRaises(ValueError):
            self.manager.plan("backup")
        self.assertEqual([], self.mutations())

    def test_failed_install_rolls_back_owned_units_and_restores_old_timer(self):
        self.runner.fail = "after-write"
        plan = self.manager.plan("backup")
        output = []
        with self.assertRaises(RuntimeError):
            self.manager.migrate("backup", plan["revision"], {}, output.append)
        self.assertFalse((self.units / "novabank-web-dev-backup.timer").exists())
        self.assertTrue((self.units / "novabank-backup.timer").exists())
        self.assertEqual({"active": True, "enabled": True}, self.runner.states["novabank-backup.timer"])
        self.assertIn("przywrócono poprzednie", "".join(output))

    def test_existing_new_timer_or_dropin_prevents_migration(self):
        self.runner.service("novabank-web-dev-backup", "prune")
        self.assertFalse(self.manager.plan("backup")["canMigrate"])
        plan = self.manager.plan("backup")
        with self.assertRaises(ValueError):
            self.manager.migrate("backup", plan["revision"], {}, lambda line: None)
        self.assertEqual([], self.mutations())
        original = self.runner
        def dropin(args, **kw):
            result = original(args, **kw)
            if args[2] == "show":
                result.stdout = result.stdout.replace("DropInPaths=", "DropInPaths=/unexpected.conf")
            return result
        self.manager.runner = dropin
        self.assertFalse(self.manager.state()["available"])

    def test_old_disabled_timer_rollback_preserves_disabled_state(self):
        self.runner.states["novabank-backup.timer"] = {"active": False, "enabled": False}
        self.runner.fail = "before-write"
        plan = self.manager.plan("backup")
        with self.assertRaises(RuntimeError):
            self.manager.migrate("backup", plan["revision"], {}, lambda line: None)
        self.assertEqual({"active": False, "enabled": False}, self.runner.states["novabank-backup.timer"])

    def test_failed_install_with_running_new_service_never_reenables_cli(self):
        self.runner.fail = "after-write"
        original = self.runner
        def running_new_service(args, **kw):
            result = original(args, **kw)
            if args[0] == "systemctl" and args[2] == "enable" and args[-1].startswith("novabank-web-"):
                self.runner.states["novabank-web-dev-backup.service"] = {"active": True}
            return result
        self.manager.runner = running_new_service
        plan = self.manager.plan("backup")
        output = []
        with self.assertRaises(RuntimeError):
            self.manager.migrate("backup", plan["revision"], {}, output.append)
        self.assertEqual({"active": False, "enabled": False}, self.runner.states["novabank-backup.timer"])
        self.assertTrue((self.units / "novabank-web-dev-backup.service").exists())
        self.assertIn("nie potwierdzono pełnego cofnięcia", "".join(output))

    def test_managed_guards_reject_partial_or_foreign_units_without_mutation(self):
        self.manager.guard_managed("backup")  # Fully absent is safe to report/remove.
        self.runner.states["novabank-backup.timer"] = {"active": False, "enabled": False}
        self.runner.service("novabank-web-dev-backup", "prune")
        self.manager.guard_managed("backup")
        service = self.units / "novabank-web-dev-backup.service"
        original_text = service.read_text()
        service.write_text(original_text + "  ExecStartPost=/unexpected-command\n")
        with self.assertRaises(ValueError):
            self.manager.guard_managed("backup")
        service.write_text("[Service]\nExecStart=/bin/bash /another/project/backup.sh prune --backup-dir /tmp/fixture\n")
        for guard in (self.manager.guard_managed, self.manager.guard_install):
            with self.assertRaises(ValueError):
                guard("backup")
        service.unlink()
        with self.assertRaises(ValueError):
            self.manager.guard_managed("backup")
        self.assertEqual([], self.mutations())

    def test_broken_working_directory_is_detected_but_can_be_repaired(self):
        self.runner.states["novabank-backup.timer"] = {"active": False, "enabled": False}
        self.runner.service("novabank-web-dev-backup", "prune")
        service = self.units / "novabank-web-dev-backup.service"
        service.write_text(service.read_text().replace(str(self.root), str(self.root).replace(" ", r"\x20"), 1))
        self.assertEqual("INVALID", self.manager.runtime("backup")["configuration"])
        self.manager.guard_install("backup")
        self.manager.install("backup", {}, lambda line: None)
        state = self.manager.runtime("backup")
        self.assertEqual("VALID", state["configuration"])
        self.assertEqual("NEVER", state["execution"])
        self.assertTrue(state["enabled"])
        self.assertEqual(1, len(list((self.root / ".novabank/control-center/web/timer-updates").glob("*/state.json"))))

    def test_execution_is_not_inferred_from_enabled_timer_or_zero_default_code(self):
        self.runner.service("novabank-web-dev-backup", "prune")
        unit = "novabank-web-dev-backup.service"
        self.assertEqual("NEVER", self.manager.runtime("backup")["execution"])
        self.runner.states[unit] = {"active": True, "started": "fixture start"}
        self.assertEqual("RUNNING", self.manager.runtime("backup")["execution"])
        self.runner.states[unit] = {"started": "fixture start", "finished": "fixture end", "code": "1", "exit": "0"}
        self.assertEqual("SUCCESS", self.manager.runtime("backup")["execution"])
        self.runner.states[unit]["result"] = "exit-code"
        self.runner.states[unit]["exit"] = "200"
        self.assertEqual("FAILED", self.manager.runtime("backup")["execution"])

    def test_update_failure_restores_only_existing_owned_pair_without_cli(self):
        self.runner.states["novabank-backup.timer"] = {"active": False, "enabled": False}
        self.runner.service("novabank-web-dev-backup", "prune")
        before = [(self.units / ("novabank-web-dev-backup" + suffix)).read_bytes() for suffix in (".service", ".timer")]
        self.runner.fail = "after-write"
        with self.assertRaises(RuntimeError):
            self.manager.install("backup", {}, lambda line: None)
        self.assertEqual(before, [(self.units / ("novabank-web-dev-backup" + suffix)).read_bytes() for suffix in (".service", ".timer")])
        self.assertEqual({"active": False, "enabled": False}, self.runner.states["novabank-backup.timer"])

    def test_partial_second_file_failure_restores_first_file_and_previous_state(self):
        self.runner.states["novabank-backup.timer"] = {"active": False, "enabled": False}
        self.runner.service("novabank-web-dev-backup", "prune")
        paths = [self.units / ("novabank-web-dev-backup" + suffix) for suffix in (".service", ".timer")]
        before = [path.read_bytes() for path in paths]
        writer = self.manager._write_unit
        failed = False
        def fail_once(path, content):
            nonlocal failed
            if path == paths[1] and content != before[1] and not failed:
                failed = True
                raise OSError("fixture second-file failure")
            writer(path, content)
        with patch.object(self.manager, "_write_unit", side_effect=fail_once), self.assertRaises(RuntimeError):
            self.manager.install("backup", {}, lambda line: None)
        self.assertTrue(failed)
        self.assertEqual(before, [path.read_bytes() for path in paths])
        self.assertEqual({"active": True, "enabled": True}, self.runner.states["novabank-web-dev-backup.timer"])

    def test_failed_rollback_does_not_reactivate_previous_broken_directory(self):
        self.runner.states["novabank-backup.timer"] = {"active": False, "enabled": False}
        self.runner.service("novabank-web-dev-backup", "prune")
        service = self.units / "novabank-web-dev-backup.service"
        service.write_text(service.read_text().replace("WorkingDirectory=" + str(self.root), "WorkingDirectory=/does-not-exist"))
        self.runner.fail = "after-write"
        output = []
        with self.assertRaises(RuntimeError):
            self.manager.install("backup", {}, output.append)
        self.assertEqual({"active": False, "enabled": False}, self.runner.states["novabank-web-dev-backup.timer"])
        self.assertIn("nie potwierdzono pełnego cofnięcia", "".join(output))
