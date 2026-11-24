import io
import json
import unittest
from unittest.mock import patch

from delivery.adapters import openai


class ProposalTest(unittest.TestCase):
    @patch.dict("os.environ", {"OPENAI_API_KEY": "fixture-only"})
    def test_review_uses_independent_stateless_structured_request(self):
        def reply(request, timeout):
            body = json.loads(request.data)
            self.assertFalse(body["store"])
            self.assertNotIn("previous_response_id", body)
            self.assertNotIn("tools", body)
            self.assertTrue(body["text"]["format"]["strict"])
            self.assertEqual(body["text"]["format"]["name"], "delivery_review")
            return io.BytesIO(
                json.dumps(
                    {
                        "status": "completed",
                        "output": [
                            {
                                "type": "message",
                                "content": [
                                    {
                                        "type": "output_text",
                                        "text": '{"passed":true,"findings":[]}',
                                    }
                                ],
                            }
                        ],
                    }
                ).encode()
            )

        with patch.object(openai, "urlopen", side_effect=reply):
            self.assertEqual(
                openai.run({"role": "review", "patch": "fixture"}),
                {"passed": True, "findings": []},
            )

    @patch.dict("os.environ", {"OPENAI_API_KEY": "fixture-only"})
    def test_incomplete_turn_does_not_pass_review(self):
        with patch.object(
            openai, "urlopen", return_value=io.BytesIO(b'{"status":"incomplete"}')
        ):
            with self.assertRaises(RuntimeError):
                openai.run({"role": "review"})
