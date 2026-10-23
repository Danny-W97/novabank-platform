"""Contract structure checks; stdlib only and no real business data or operations."""
from dataclasses import asdict
import json
import unittest

from openapi import document
from server import ACTIONS


class OpenApiTests(unittest.TestCase):
    def setUp(self):
        self.spec = document([asdict(action) for action in ACTIONS])

    def test_all_references_operation_ids_and_path_parameters(self):
        def walk(value):
            if isinstance(value, dict):
                if "$ref" in value:
                    parts = value["$ref"].split("/")
                    self.assertEqual("#", parts[0])
                    target = self.spec
                    for part in parts[1:]:
                        target = target[part]
                for item in value.values():
                    walk(item)
            elif isinstance(value, list):
                for item in value:
                    walk(item)
        walk(self.spec)
        operation_ids = set()
        for path, methods in self.spec["paths"].items():
            for operation in methods.values():
                self.assertNotIn(operation["operationId"], operation_ids)
                operation_ids.add(operation["operationId"])
                self.assertIn("403", operation["responses"])
                if "{id}" in path:
                    parameter = operation["parameters"][0]
                    self.assertEqual("path", parameter["in"])
                    self.assertTrue(parameter["required"])
        self.assertEqual(28, len(operation_ids))
        json.dumps(self.spec)

    def test_token_security_matches_actual_read_and_write_handlers(self):
        secured_get = {"/api/accounts", "/api/bank-accounts", "/api/bank-accounts/{id}", "/api/shop-accounts", "/api/shop-accounts/{id}", "/api/operations/settings", "/api/operations/legacy", "/api/notifications", "/api/notifications/{id}", "/api/config/dev", "/api/config/files", "/api/support/{id}","/api/payment-cases","/api/payment-cases/{id}"}
        for path, methods in self.spec["paths"].items():
            for method, operation in methods.items():
                self.assertEqual(method == "post" or path in secured_get, bool(operation["security"]))
        token = self.spec["components"]["securitySchemes"]["ControlCenterToken"]
        self.assertEqual("X-Control-Center-Token", token["name"])
        self.assertEqual("header", token["in"])

    def test_action_profiles_confirmation_and_cash_from_real_allowlist(self):
        branches = self.spec["components"]["schemas"]["JobRequest"]["oneOf"]
        self.assertEqual(len(ACTIONS), len(branches))
        for action, branch in zip(ACTIONS, branches):
            properties = branch["properties"]
            self.assertEqual(action.id, properties["action"]["const"])
            self.assertEqual(list(action.profiles), properties["profile"]["enum"])
            self.assertEqual(action.changes, properties["confirmed"].get("const") is True)
            self.assertFalse(branch["additionalProperties"])
            from production_operations import PARAMETERS
            needs_parameters = action.id.startswith(("cash-", "migrate-cli-", "payment-case-")) or action.id in ("purchase-cancel","purchase-refresh","artifacts-delete", "repair-metrics-dev", "backup-totp-verify", "payment-history-migration-apply") or any(PARAMETERS.get(action.id, {}).values())
            self.assertEqual(needs_parameters, "parameters" in branch["required"])
            if "prod" in properties["profile"]["enum"]:
                self.assertIn("productionConfirmation", properties)
                self.assertEqual("PROD", branch["allOf"][0]["then"]["properties"]["productionConfirmation"]["const"])

    def test_notification_bulk_and_single_contracts(self):
        branches = self.spec["components"]["schemas"]["NotificationRequest"]["oneOf"]
        for branch in branches:
            properties = branch["properties"]
            operation = properties["operation"]["const"]
            self.assertEqual(operation != "delete-active", "id" in properties)
            self.assertEqual(operation.startswith("delete"), properties["confirmed"].get("const") is True)
            self.assertFalse(branch["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
