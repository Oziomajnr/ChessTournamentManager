import unittest

from delivery.agents import replacements


class EditsTest(unittest.TestCase):
    def test_sequential_replacements_and_new_files(self):
        files = {"src/A.kt": "one two\n"}
        edits = [
            {"path": "src/A.kt", "before": "one", "after": "green"},
            {"path": "src/A.kt", "before": "green two", "after": "three"},
            {"path": "src/B.kt", "before": "", "after": "new"},
        ]
        self.assertEqual(
            replacements(files, edits, ["src/"]),
            {"src/A.kt": "three\n", "src/B.kt": "new"},
        )

    def test_ambiguous_context_and_path_escape(self):
        for path, before in [
            ("src/A.kt", "x"),
            ("src/../.github/x.kt", ""),
            ("/src/A.kt", ""),
            ("src/.hooks/a.kt", ""),
        ]:
            with self.assertRaises(ValueError):
                replacements(
                    {"src/A.kt": "xx"},
                    [{"path": path, "before": before, "after": "v"}],
                    ["src/"],
                )
