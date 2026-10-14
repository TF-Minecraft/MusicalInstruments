import base64
import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid
import xml.etree.ElementTree as ET
import yaml

spec = importlib.util.spec_from_file_location("studio_ownership", Path(__file__).with_name("studio-ownership.py"))
studio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(studio)


class RollbackTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.key, self.account, self.old, self.recipient, self.new, self.death = [str(uuid.uuid4()) for _ in range(6)]
        self.source = self.root / "character-projects" / self.old / (self.key + ".yml")
        self.target = self.root / "character-projects" / self.new / (self.key + ".yml")
        self.journal = self.root / "ownership/estates" / (self.death + ".xml")
        self.recovery = self.root / "new-recovery"
        self.original = {"format": 2, "id": self.key, "owner": self.account, "owner-account": self.account,
                         "owner-character": self.old, "title": "Preserve title", "author": "Original author",
                         "tracks": [{"notes": [], "extension": None}], "future": {"nested": [None, 1], "binary": bytes([0, 1, 2])}}
        self.output = dict(self.original, **{"owner-account": self.recipient, "owner-character": self.new})
        self.before, self.after = [yaml.safe_dump(row).encode() for row in (self.original, self.output)]
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(self.after)
        self.journal.parent.mkdir(parents=True)
        decision = f"EstateDecision[deathId={self.death}, testator={studio.reference(self.account, self.old)}, diedAt=Optional.empty, property=Character[ref={studio.reference(self.recipient, self.new)}], guildLocks=Frozen[], revision=1]"
        self.fields = {"format": "1", "kind": "estate", "death": self.death, "from-account": self.account,
                       "from-character": self.old, self.key + ".state": "moved", self.key + ".decision": decision,
                       self.key + ".revision": "1", self.key + ".from-account": self.account,
                       self.key + ".from-character": self.old, self.key + ".to-account": self.recipient,
                       self.key + ".to-character": self.new, self.key + ".source": str(self.source.relative_to(self.root)),
                       self.key + ".target": str(self.target.relative_to(self.root)),
                       self.key + ".before": base64.b64encode(self.before).decode(),
                       self.key + ".after": base64.b64encode(self.after).decode(),
                       self.key + ".before-hash": studio.digest(self.before), self.key + ".after-hash": studio.digest(self.after)}
        self.write(self.fields)

    def write(self, fields):
        element = ET.Element("properties")
        for key, value in fields.items():
            ET.SubElement(element, "entry", key=key).text = value
        ET.ElementTree(element).write(self.journal)

    def apply(self, kind="estates"):
        return studio.inspect(self.root, kind, True, self.recovery)

    def payload(self, fields, part, row):
        data = yaml.safe_dump(row).encode()
        fields[self.key + "." + part] = base64.b64encode(data).decode()
        fields[self.key + "." + part + "-hash"] = studio.digest(data)

    def test_preview_then_apply_restore_exact_original_preserve_current_and_archive_receipt(self):
        report = studio.inspect(self.root, "estates")
        self.assertEqual(report["journals"][0]["records"][0]["result"], "safe offline rollback")
        self.assertFalse(self.source.exists())
        result = self.apply()
        self.assertEqual(self.source.read_bytes(), self.before)
        self.assertFalse(self.target.exists())
        self.assertEqual(self.after, (self.recovery / self.target.relative_to(self.root)).read_bytes())
        self.assertIn("archived", result["journals"][0])
        self.assertFalse(self.journal.exists())

    def test_later_gameplay_edits_are_reported_and_preserved(self):
        self.target.write_bytes(b"edited since inheritance")
        result = self.apply()
        self.assertIn("conflict", result["journals"][0]["records"][0]["result"])
        self.assertEqual(self.target.read_bytes(), b"edited since inheritance")
        self.assertTrue(self.journal.exists())
        self.assertFalse(self.source.exists())

    def test_invalid_backups_paths_and_missing_copies_are_held(self):
        self.target.unlink()
        report = self.apply()
        self.assertIn("both copies missing", report["journals"][0]["records"][0]["result"])
        self.assertRaises(ValueError, studio.safe, self.root, "../outside")
        self.journal.write_text("broken XML")
        self.assertIn("error", studio.inspect(self.root, "estates")["journals"][0])
        self.assertRaises(ValueError, studio.inspect, self.root, "estates", True)
        self.assertRaises(FileExistsError, studio.inspect, self.root, "estates", True, self.recovery)

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
        fields = dict(self.fields, **{"held.opaque.state": "retained unknown record", "held-source.opaque.state": base64.b64encode(b"opaque").decode()})
        self.write(fields)
        report = studio.inspect(self.root, "estates")["journals"][0]
        self.assertNotIn("error", report)
        self.assertEqual(len(report["records"]), 1)
        self.assertEqual(report["held"]["held.opaque.state"], "retained unknown record")

    def test_hash_consistent_title_tracks_unknown_null_and_recipient_changes_do_not_restore(self):
        for field in ("title", "tracks", "future", "owner-account", "owner-character"):
            fields = dict(self.fields)
            output = copy.deepcopy(self.output)
            output[field] = str(uuid.uuid4()) if field.startswith("owner-") else "changed content"
            if field.startswith("owner-"):
                fields[self.key + "." + field.replace("owner-", "to-")] = output[field]
            self.payload(fields, "after", output)
            self.write(fields)
            result = studio.inspect(self.root, "estates")
            self.assertIn("error", result["journals"][0], field)
            self.assertFalse(self.source.exists())
            self.assertEqual(self.after, self.target.read_bytes())

    def test_paths_full_refs_schemas_and_decisions_are_checked_before_any_restore(self):
        for field, value in [("source", "../outside"), ("source", f"character-projects/{self.new}/{self.key}.yml"),
                             ("target", str(self.source.relative_to(self.root))), ("before-hash", "wrong"),
                             ("decision", "wrong"), ("revision", "2"), ("state", "unknown"), ("from-account", str(uuid.uuid4()))]:
            fields = dict(self.fields)
            fields[self.key + "." + field] = value
            self.write(fields)
            self.assertIn("error", studio.inspect(self.root, "estates")["journals"][0], field)
            self.assertFalse(self.source.exists())
            self.assertEqual(self.after, self.target.read_bytes())
        for format_value in (1, "2", 2.0, True):
            fields = dict(self.fields)
            self.payload(fields, "after", dict(self.output, format=format_value))
            self.write(fields)
            self.assertIn("schema", studio.inspect(self.root, "estates")["journals"][0]["error"])
            self.assertFalse(self.source.exists())
            self.assertEqual(self.after, self.target.read_bytes())

    def test_invalid_later_entry_never_restores_an_earlier_valid_record(self):
        later = str(uuid.UUID(int=(1 << 128) - 1))
        if self.key == later:
            self.fail("unexpected fixture UUID")
        fields = dict(self.fields, **{later + ".state": "moved"})
        self.write(fields)
        before = self.journal.read_bytes()
        result = self.apply()
        self.assertIn("error", result["journals"][0])
        self.assertFalse(self.source.exists())
        self.assertEqual(self.after, self.target.read_bytes())
        self.assertEqual(before, self.journal.read_bytes())

    def test_duplicate_xml_and_nested_yaml_fields_are_rejected_without_mutation(self):
        element = ET.parse(self.journal).getroot()
        ET.SubElement(element, "entry", key="format").text = "1"
        ET.ElementTree(element).write(self.journal)
        self.assertIn("duplicate XML", studio.inspect(self.root, "estates")["journals"][0]["error"])
        fields = dict(self.fields)
        after = self.after + b"future: {nested: null, nested: 1}\n"
        fields[self.key + ".after"] = base64.b64encode(after).decode()
        fields[self.key + ".after-hash"] = studio.digest(after)
        self.write(fields)
        self.assertIn("duplicate YAML", studio.inspect(self.root, "estates")["journals"][0]["error"])
        self.assertFalse(self.source.exists())
        self.assertEqual(self.after, self.target.read_bytes())

    def test_recovery_write_failure_preserves_every_original_copy(self):
        original = studio.durable
        def failure(path, data):
            if path == self.recovery / self.target.relative_to(self.root):
                raise OSError("recovery disk full")
            original(path, data)
        with patch.object(studio, "durable", side_effect=failure):
            result = self.apply()
        self.assertIn("error", result["journals"][0])
        self.assertFalse(self.source.exists())
        self.assertEqual(self.after, self.target.read_bytes())
        self.assertTrue(self.journal.exists())

    def test_legacy_mapping_round_trip_preserves_raw_nulls_and_fixed_authority(self):
        self.journal.unlink()
        self.target.unlink()
        self.source = self.root / "projects" / self.account / (self.key + ".yml")
        self.source.parent.mkdir(parents=True)
        original = dict(self.original)
        del original["owner-account"], original["owner-character"]
        before = yaml.safe_dump(original).encode()
        self.source.write_bytes(before)
        output = dict(original, **{"owner-account": self.account, "owner-character": self.new})
        after = yaml.safe_dump(output).encode()
        self.target.write_bytes(after)
        self.journal = self.root / "ownership/migration" / (self.account + ".xml")
        self.journal.parent.mkdir()
        fields = dict(self.fields, kind="migration", **{"from-account": self.account, "to-account": self.account, "to-character": self.new, self.key + ".to-account": self.account, self.key + ".source": str(self.source.relative_to(self.root))})
        self.payload(fields, "before", original)
        self.payload(fields, "after", output)
        self.write(fields)
        self.assertEqual("safe offline rollback", studio.inspect(self.root, "migration")["journals"][0]["records"][0]["result"])
        result = self.apply("migration")
        self.assertIn("archived", result["journals"][0])
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(after, (self.recovery / self.target.relative_to(self.root)).read_bytes())
        self.assertFalse(self.target.exists())
    def test_unknown_scalar_types_cannot_change_through_python_numeric_equality(self):
        for value in (True, 1.0):
            fields = dict(self.fields)
            output = copy.deepcopy(self.output)
            output["future"]["nested"][1] = value
            self.payload(fields, "after", output)
            self.write(fields)
            self.assertIn("changes project content", studio.inspect(self.root, "estates")["journals"][0]["error"])
            self.assertFalse(self.source.exists())
            self.assertEqual(self.after, self.target.read_bytes())

    def test_chain_with_older_journal_newer_mtime_requires_checked_fresh_backup_passes(self):
        first = self.journal
        account, character, death = [str(uuid.uuid4()) for _ in range(3)]
        last = self.root / 'character-projects' / character / (self.key + '.yml')
        last.parent.mkdir(parents=True)
        final = dict(self.output, **{'owner-account': account, 'owner-character': character})
        final_bytes = yaml.safe_dump(final).encode()
        last.write_bytes(final_bytes)
        self.target.unlink()
        self.journal = first.with_name(death + '.xml')
        fields = dict(self.fields, death=death, **{'from-account': self.recipient, 'from-character': self.new,
            self.key + '.from-account': self.recipient, self.key + '.from-character': self.new,
            self.key + '.to-account': account, self.key + '.to-character': character,
            self.key + '.source': str(self.target.relative_to(self.root)), self.key + '.target': str(last.relative_to(self.root)),
            self.key + '.decision': f'EstateDecision[deathId={death}, testator={studio.reference(self.recipient, self.new)}, diedAt=Optional.empty, property=Character[ref={studio.reference(account, character)}], guildLocks=Frozen[], revision=1]'})
        self.payload(fields, 'before', self.output)
        self.payload(fields, 'after', final)
        self.write(fields)
        os.utime(first, ns=(3_000_000, 3_000_000))
        os.utime(self.journal, ns=(1_000_000, 1_000_000))
        original_first = first.read_bytes()
        preview = studio.inspect(self.root, 'estates')
        self.assertIn('both copies missing', preview['journals'][0]['records'][0]['result'])
        self.assertEqual('safe offline rollback', preview['journals'][1]['records'][0]['result'])
        result = self.apply()
        self.assertNotIn('archived', result['journals'][0])
        self.assertIn('archived', result['journals'][1])
        self.assertEqual(original_first, first.read_bytes())
        self.assertEqual(self.after, self.target.read_bytes())
        self.assertFalse(last.exists())
        self.assertEqual(final_bytes, (self.recovery / last.relative_to(self.root)).read_bytes())
        again = studio.inspect(self.root, 'estates')
        self.assertEqual('safe offline rollback', again['journals'][0]['records'][0]['result'])
        next_backup = self.root / 'next-recovery'
        final_report = studio.inspect(self.root, 'estates', True, next_backup)
        self.assertIn('archived', final_report['journals'][0])
        self.assertEqual(self.before, self.source.read_bytes())
        self.assertEqual(self.after, (next_backup / self.target.relative_to(self.root)).read_bytes())
        self.assertFalse(self.target.exists())
        self.assertEqual([], studio.inspect(self.root, 'estates')['journals'])



if __name__ == "__main__":
    unittest.main()
