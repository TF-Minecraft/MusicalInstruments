import base64
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("studio_ownership", Path(__file__).with_name("studio-ownership.py"))
studio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(studio)


class RollbackTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / "character-projects/old/project.yml"
        self.target = self.root / "character-projects/new/project.yml"
        self.journal = self.root / "ownership/estates/death.xml"
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(b"after")
        self.journal.parent.mkdir(parents=True)
        fields = {"format": "1", "kind": "estate", "project.state": "moved",
                  "project.source": str(self.source.relative_to(self.root)),
                  "project.target": str(self.target.relative_to(self.root)),
                  "project.before": base64.b64encode(b"before").decode(),
                  "project.before-hash": studio.digest(b"before"),
                  "project.after-hash": studio.digest(b"after")}
        element = ET.Element("properties")
        for key, value in fields.items():
            ET.SubElement(element, "entry", key=key).text = value
        ET.ElementTree(element).write(self.journal)

    def test_preview_then_apply_restore_exact_original_and_archive_receipt(self):
        report = studio.inspect(self.root, "estates")
        self.assertEqual(report["journals"][0]["records"][0]["result"], "safe offline rollback")
        self.assertFalse(self.source.exists())
        result = studio.inspect(self.root, "estates", True)
        self.assertEqual(self.source.read_bytes(), b"before")
        self.assertFalse(self.target.exists())
        self.assertIn("archived", result["journals"][0])
        self.assertFalse(self.journal.exists())

    def test_later_gameplay_edits_are_reported_and_preserved(self):
        self.target.write_bytes(b"edited since inheritance")
        result = studio.inspect(self.root, "estates", True)
        self.assertIn("conflict", result["journals"][0]["records"][0]["result"])
        self.assertEqual(self.target.read_bytes(), b"edited since inheritance")
        self.assertTrue(self.journal.exists())
        self.assertFalse(self.source.exists())

    def test_invalid_backups_paths_and_missing_copies_are_held(self):
        self.target.unlink()
        report = studio.inspect(self.root, "estates", True)
        self.assertIn("both copies missing", report["journals"][0]["records"][0]["result"])
        self.assertRaises(ValueError, studio.safe, self.root, "../outside")
        self.journal.write_text("broken XML")
        self.assertIn("error", studio.inspect(self.root, "estates", True)["journals"][0])

    def test_new_projects_and_unassigned_legacy_data_are_listed(self):
        extra = self.target.parent / "newly-created.yml"
        extra.write_bytes(b"preserve")
        legacy = self.root / "projects/unreviewed"
        legacy.mkdir(parents=True)
        result = studio.inspect(self.root, "estates")
        self.assertEqual(result["unplanned_projects"], [str(extra)])
        self.assertEqual(result["unassigned_legacy"], [str(legacy)])
        self.assertTrue(extra.exists())

    def test_held_state_filename_is_reported_without_becoming_a_transfer(self):
        element = ET.parse(self.journal).getroot()
        ET.SubElement(element, "entry", key="held.opaque.state").text = "retained unknown record"
        ET.SubElement(element, "entry", key="held-source.opaque.state").text = base64.b64encode(b"opaque").decode()
        ET.ElementTree(element).write(self.journal)
        report = studio.inspect(self.root, "estates")["journals"][0]
        self.assertNotIn("error", report)
        self.assertEqual(len(report["records"]), 1)
        self.assertEqual(report["held"]["held.opaque.state"], "retained unknown record")


if __name__ == "__main__":
    unittest.main()
