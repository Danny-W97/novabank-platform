"""Offline Bash fixtures. Docker/sudo/npm are tiny recording executables."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipIf(os.name == "nt", "Bash fixtures are verified in WSL/Linux")
class BashDispatcherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for relative in ("scripts/control-center/control-center.sh", "scripts/control-center/web-operations.sh", "scripts/backup/backup.sh", "scripts/monitoring/monitoring.sh", "infra/monitoring/.env.monitoring.example", "infra/monitoring/prometheus/prometheus.yml.example", "scripts/control-center/web/dev_env_layout.py", "scripts/control-center/web/dev_configuration.py", "scripts/control-center/web/configuration_files.py", "scripts/control-center/web/env_ownership.py", "scripts/control-center/web/operator_settings.py"):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, path)
        self.bin = self.root / "bin"
        shutil.copyfile(ROOT / "scripts/control-center/flyway-status.sql", self.root / "scripts/control-center/flyway-status.sql")
        self.bin.mkdir()
        self.record = self.root / "calls"
        self.environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"], QA_CALLS=str(self.record))
        self.environment.pop("DOCKER_HOST", None)
        self.fake("docker", 'printf "docker %s\\n" "$*" >> "$QA_CALLS"\nif [[ "$1" == context ]]; then printf "unix:///fixture/docker.sock\\n"; fi\n')
        self.fake("sudo", 'printf "sudo %s\\n" "$*" >> "$QA_CALLS"\n')
        self.fake("npm", 'printf "npm %s\\n" "$*" >> "$QA_CALLS"\n')

    def tearDown(self):
        self.temp.cleanup()

    def fake(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/bash\n" + body)
        path.chmod(0o700)

    def run_bash(self, script, *arguments, **environment):
        return subprocess.run(["bash", str(self.root / script), *arguments], env=self.environment | environment, capture_output=True, timeout=5)

    def test_prod_and_test_mutation_dispatches_are_rejected_before_docker(self):
        for action, profile in (("infra-up", "prod"), ("monitoring-start", "test"), ("backup-create", "test"), ("stripe-listen", "test")):
            result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", action, profile)
            self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.record.exists())

    def test_remote_docker_rejected_without_running_monitoring(self):
        result = self.run_bash("scripts/control-center/web-operations.sh", "monitoring-start", "dev", DOCKER_HOST="tcp://remote.example:2375")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.record.exists())

    def test_selected_installation_commands_are_fixed_and_noninteractive(self):
        # Real apt is not called: sudo is a recording mock, including sudo -n true.
        result = self.run_bash("scripts/control-center/web-operations.sh", "install-tools", "test")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertIn("sudo -n apt-get update", self.record.read_text())
        self.assertIn("sudo -n apt-get install -y", self.record.read_text())

    def test_backup_web_mode_does_not_source_env_shell_payload(self):
        env = self.root / ".novabank/env"
        env.mkdir(parents=True)
        marker = self.root / "must-not-exist"
        (env / "project.env").write_text(f"touch '{marker}'\nPOSTGRES_DB=bankdb\nPOSTGRES_USER=bankuser\nPOSTGRES_PASSWORD=fixture-password\nSHOP_POSTGRES_DB=shopdb\nSHOP_POSTGRES_USER=shopuser\nSHOP_POSTGRES_PASSWORD=fixture-password\n")
        # Report is read-only and intentionally absent. No dumps/DB mutations.
        result = self.run_bash("scripts/backup/backup.sh", "dr-report", NOVABANK_WEB_MODE="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(marker.exists())

    def test_timer_dry_run_pins_directory_retention_and_migration_marker(self):
        directory = self.root / ".novabank/backups/custom"
        result = self.run_bash("scripts/backup/backup.sh", "schedule-install", "--dry-run", "--backup-dir", str(directory), "--daily-at", "21:35",
                               NOVABANK_WEB_MODE="1", NOVABANK_WEB_BACKUP_DIRECTORY=str(directory), NOVABANK_WEB_BACKUP_RETENTION_DAYS="45", NOVABANK_WEB_MIGRATION_ID="a" * 32, DOCKER_HOST="unix:///fixture/docker.sock")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        output = result.stdout.decode()
        self.assertIn("OnCalendar=*-*-* 21:35:00", output)
        self.assertIn("Environment=NOVABANK_WEB_BACKUP_RETENTION_DAYS=45", output)
        self.assertIn("NOVABANK_WEB_BACKUP_DIRECTORY=" + str(directory), output)
        self.assertEqual(2, output.count("# NovaBank migration " + "a" * 32))
        self.assertFalse(directory.exists(), "dry-run must not register a backup location")
        self.assertFalse(self.record.exists(), "no Docker/systemctl action in dry-run")

    def test_adapter_passes_validated_custom_schedule_without_shell_execution(self):
        directory = self.root / ".novabank/backups/custom"
        self.fake("bash", 'printf "inner-bash %s\\n" "$*" >> "$QA_CALLS"\n')
        result = subprocess.run(["/bin/bash", str(self.root / "scripts/control-center/web-operations.sh"), "dr-schedule-install", "dev"],
                                env=self.environment | {"NOVABANK_WEB_BACKUP_DIRECTORY": str(directory), "NOVABANK_WEB_DR_WEEKDAY": "MON", "NOVABANK_WEB_DR_AT": "22:10"}, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertIn("dr-schedule-install --backup-dir " + str(directory) + " --weekday MON --at 22:10", self.record.read_text())
        self.assertTrue((directory / ".novabank-dev-backup-owner.json").exists())

    def test_both_working_directories_preserve_spaces_and_escape_only_specifiers(self):
        project = self.root / "Bank Workspace"
        shutil.copytree(self.root / "scripts", project / "scripts")
        for command in ("schedule-install", "dr-schedule-install"):
            result = subprocess.run(["/bin/bash", str(project / "scripts/backup/backup.sh"), command, "--dry-run"],
                                    env=self.environment | {"NOVABANK_WEB_MODE": "1", "DOCKER_HOST": "unix:///fixture/docker.sock"}, capture_output=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr.decode())
            line = next(line for line in result.stdout.decode().splitlines() if line.startswith("WorkingDirectory="))
            self.assertEqual("WorkingDirectory=" + str(project).replace("%", "%%"), line)
            self.assertNotIn(r"\x20", line)

    def test_monitoring_uses_project_metrics_and_configured_management_ports(self):
        env = self.root / ".novabank/env"
        env.mkdir(parents=True)
        password = "@fixture-long-metrics-password"
        (env / "project.env").write_text(f"METRICS_USERNAME=fixture-reader\nMETRICS_PASSWORD={password}\n")
        (env / "dev.env").write_text("BANK_MANAGEMENT_PORT=18082\nSHOP_MANAGEMENT_PORT=18083\n")
        result = self.run_bash("scripts/control-center/web-operations.sh", "monitoring-setup", "dev")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        yaml = (self.root / ".novabank/monitoring/prometheus.yml").read_text()
        self.assertIn('localhost:18082', yaml)
        self.assertIn('localhost:18083', yaml)
        self.assertIn("password: '" + password + "'", yaml)
        self.assertIn("METRICS_PASSWORD=" + password, (env / "dev.env").read_text())
        self.assertNotIn(password, result.stdout.decode())

    def test_web_command_does_not_create_production_directories(self):
        result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", "check-java", "test")
        self.assertFalse((self.root / ".novabank/tls").exists())
        self.assertFalse((self.root / ".novabank/env/prod.bank.env").exists())

    def test_flyway_status_uses_shared_read_query_for_both_databases(self):
        env = self.root / ".novabank/env"
        env.mkdir(parents=True)
        (env / "project.env").write_text("POSTGRES_DB=bankdb\nSHOP_POSTGRES_DB=shopdb\n")
        result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", "flyway-status", "dev")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        calls = self.record.read_text()
        self.assertIn("exec bank-postgres psql", calls)
        self.assertIn("exec shop-postgres psql", calls)
        self.assertEqual(2, calls.count("WHERE success AND version IS NOT NULL"))
        self.assertEqual(2, calls.count("ORDER BY installed_rank DESC"))
        self.assertNotIn("COALESCE(MAX(version)", calls)
        self.assertIn("liczba wpisow historii, nie numer wersji", result.stdout.decode())

    def test_missing_flyway_query_fails_without_sending_empty_sql(self):
        env = self.root / ".novabank/env"
        env.mkdir(parents=True)
        (env / "project.env").write_text("POSTGRES_DB=bankdb\n")
        (self.root / "scripts/control-center/flyway-status.sql").unlink()
        result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", "flyway-status", "dev")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Brak pliku zapytania", result.stderr.decode())
        self.assertNotIn("psql", self.record.read_text())

    def test_compose_receives_project_then_dev_file_not_secret_arguments(self):
        env = self.root / ".novabank/env"
        env.mkdir(parents=True)
        (env / "project.env").write_text("POSTGRES_DB=fixture\n")
        (env / "dev.env").write_text("POSTGRES_PASSWORD=fixture-only-password\n")
        result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", "infra-up", "dev")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        calls = self.record.read_text()
        self.assertIn(f"--env-file {env}/project.env --env-file {env}/dev.env", calls)
        self.assertNotIn("fixture-only-password", calls)

    def test_cli_generator_uses_sole_owners_and_keeps_keys_on_second_run(self):
        from dev_configuration import assignments
        result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", "dev-env", "dev")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        env = self.root / ".novabank/env"
        project = assignments((env / "project.env").read_bytes())
        dev = assignments((env / "dev.env").read_bytes())
        self.assertFalse(project.keys() & dev.keys())
        self.assertNotIn("POSTGRES_PASSWORD", project)
        self.assertIn("POSTGRES_PASSWORD", dev)
        result = self.run_bash("scripts/control-center/control-center.sh", "--web-action", "dev-env", "dev")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        after = assignments((env / "dev.env").read_bytes())
        for key in ("BANK_API_KEY", "TOTP_ENCRYPTION_KEY", "POSTGRES_PASSWORD"):
            self.assertEqual(dev[key], after[key])


if __name__ == "__main__":
    unittest.main()
