"""Safe visual QA fixture: temporary config, no Docker/Maven/Stripe operations."""
import argparse
from pathlib import Path
import tempfile

from server import Panel, Server


class PreviewPanel(Panel):
    def submit(self, *args, **kwargs):
        raise ValueError("Podgląd QA: operacje środowiska są wyłączone. Edytor używa wyłącznie plików tymczasowych.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=61609)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="novabank-files-preview-") as directory:
        panel = PreviewPanel(Path(directory))
        panel.dev_configuration.save(panel.dev_configuration.state()["revision"], {}, True)
        state = panel.configuration_files.state("test")
        panel.configuration_files.save("test", "test", state["revision"], {"TEST_BANK_API_KEY": "fixture-only-not-a-real-key"}, [], True, False)
        with Server(args.port, panel) as server:
            print(f"Local QA: http://127.0.0.1:{args.port}", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                panel.close()
