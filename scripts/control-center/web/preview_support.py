"""Owned local QA server: fake configuration; only the file-only repair can execute."""
import argparse
from pathlib import Path
import shutil
import tempfile

from dev_configuration import upsert
from problem_support import REPAIR_ACTION
from server import Panel, Server


class SupportPreview(Panel):
    def submit(self, action_id, *args, **kwargs):
        if action_id != REPAIR_ACTION or not self.root.name.startswith("novabank-support-preview-"):
            raise ValueError("Podgląd QA: prawdziwe operacje środowiska są wyłączone.")
        return super().submit(action_id, *args, **kwargs)

    def run_command(self, *args, **kwargs):
        raise AssertionError("Preview never runs shell/service commands.")

    def start_apps(self, *args, **kwargs):
        raise AssertionError("Preview never starts applications.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=61624)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="novabank-support-preview-") as directory:
        panel = SupportPreview(Path(directory))
        panel.dev_configuration.save(panel.dev_configuration.state()["revision"], {}, True)
        dev = panel.root / ".novabank/env/dev.env"
        dev.write_bytes(upsert(dev.read_bytes(), {"METRICS_USERNAME": "fixture-reader", "METRICS_PASSWORD": "fixture-existing-dev-password"}))
        monitoring = panel.root / ".novabank/monitoring"
        monitoring.mkdir()
        (monitoring / ".env.monitoring").write_text("METRICS_USERNAME=old-reader\nMETRICS_PASSWORD=fixture-old-monitor-password\nGRAFANA_ADMIN_PASSWORD=fixture-grafana-keep\n")
        template = Path(__file__).resolve().parents[3] / "infra/monitoring/prometheus/prometheus.yml.example"
        shutil.copyfile(template, monitoring / "prometheus.yml")
        panel.notify_problem("CONFIG_REVISION_CHANGED", "dev")
        panel.notify_problem("BUILD_REQUIRED", "dev", context={"component": "bank"})
        panel.notify_problem("METRICS_CREDENTIALS_CONFLICT", "dev")
        with Server(args.port, panel) as server:
            print(f"Local QA: http://127.0.0.1:{args.port}", flush=True)
            print("Owned fixture root: " + str(panel.root), flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                panel.close()
