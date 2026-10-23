"""Public certificate fixtures and mocked sockets; no live Vault or systemctl."""
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest.mock import patch, MagicMock

from vault_diagnostics import diagnose
from problems import ProblemError
from production_operations import ProductionOperations


class VaultDiagnosticsTests(unittest.TestCase):
    def test_missing_ca_reports_instruction_without_unverified_connection(self):
        with tempfile.TemporaryDirectory() as directory:
            output = []
            with patch("vault_diagnostics.shutil.which", return_value=None), patch("vault_diagnostics.socket.create_connection") as connection:
                with self.assertRaises(ProblemError) as error:
                    diagnose(Path(directory), output.append)
            self.assertEqual("VAULT_CA_NOT_READY", error.exception.issue_code)
            connection.assert_not_called()
            self.assertIn("Nie pobierano certyfikatu z wyłączoną weryfikacją", "".join(output))

    def test_success_uses_configured_ca_hostname_and_emits_only_public_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            selected = root / ".novabank/tls/vault/vault.crt"
            selected.parent.mkdir(parents=True); selected.write_text("fixture-public-certificate")
            context = MagicMock()
            context.wrap_socket.return_value.__enter__.return_value.getpeercert.return_value = b"fixture-public-DER"
            output = []
            with patch("vault_diagnostics.shutil.which", return_value=None), patch("vault_diagnostics.ssl.create_default_context", return_value=context) as factory, patch("vault_diagnostics.socket.create_connection", return_value=MagicMock()) as connect:
                diagnose(root, output.append)
            factory.assert_called_once_with(cafile=str(selected))
            self.assertEqual("127.0.0.1", context.wrap_socket.call_args.kwargs["server_hostname"])
            connect.assert_called_once_with(("127.0.0.1", 8200), timeout=5)
            self.assertIn("TLS potwierdzony", "".join(output))
            self.assertNotIn("fixture-public-DER", "".join(output))
            self.assertEqual("fixture-public-certificate", selected.read_text())

    def test_diagnostic_does_not_snapshot_bootstrap_or_read_business_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            operations = ProductionOperations(Path(directory))
            with patch.object(operations.files, "snapshot", side_effect=AssertionError("No bootstrap read")), patch("vault_diagnostics.diagnose") as diagnostic:
                operations.run("vault-ca-diagnostics", {}, lambda line: None)
            diagnostic.assert_called_once()
