import unittest
from unittest.mock import patch
from audit_extended_verify import require_local_docker


class AuditTargetTests(unittest.TestCase):
    def test_remote_host_rejected_before_any_docker_call(self):
        with patch('audit_extended_verify.subprocess.check_output') as call:
            with self.assertRaises(ValueError):require_local_docker({'DOCKER_HOST':'tcp://remote.invalid:2375'})
            call.assert_not_called()
    def test_selected_context_must_also_be_local(self):
        with patch('audit_extended_verify.subprocess.check_output',return_value=b'ssh://remote.invalid\n'):
            with self.assertRaises(ValueError):require_local_docker({})
        with patch('audit_extended_verify.subprocess.check_output',return_value=b'unix:///var/run/docker.sock\n'):
            require_local_docker({})


if __name__=='__main__':unittest.main()
