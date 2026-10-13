#!/usr/bin/env python3
"""Inspect studio ownership journals and preview/apply an offline guarded rollback."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import uuid
import xml.etree.ElementTree as ET

import yaml


class ProjectLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in result:
                raise ValueError("duplicate YAML field")
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def project(data):
    value = yaml.load(data, Loader=ProjectLoader)
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value) or type(value.get("format")) is not int or value["format"] != 2:
        raise ValueError("unsupported project schema")
    identity(value["id"])
    return value


def identity(value):
    if str(uuid.UUID(value)) != value:
        raise ValueError("invalid ownership identity")
    return value


def reference(account, character):
    return f"CharacterRef[ownerId={account}, characterId={character}]"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("journal path escapes studio root")
    return path


def durable(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix="studio-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        force_ancestors(path.parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def force(folder):
    descriptor = os.open(folder, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def force_ancestors(folder):
    force(folder)
    for parent in folder.parents:
        force(parent)


def journal_fields(file):
    root = ET.parse(file).getroot()
    if root.tag != "properties":
        raise ValueError("unsupported XML envelope")
    result = {}
    for entry in root.findall("entry"):
        key = entry.attrib["key"]
        if key in result:
            raise ValueError("duplicate XML field")
        result[key] = entry.text or ""
    return result


def captured_decision(fields, key):
    decision = fields[key + ".decision"]
    prefix = f"EstateDecision[deathId={fields['death']}, testator={reference(fields[key + '.from-account'], fields[key + '.from-character'])}, diedAt="
    recipient = f", property=Character[ref={reference(fields[key + '.to-account'], fields[key + '.to-character'])}], guildLocks="
    suffix = re.search(r", revision=(0|[1-9][0-9]*)]$", decision)
    if not decision.startswith(prefix) or recipient not in decision or suffix is None:
        raise ValueError("project plan differs from captured full decision")
    revision = suffix.group(1)
    if fields[key + ".revision"] != revision or int(revision) > 9223372036854775807:
        raise ValueError("unsupported captured estate revision")


def same_content(left, right):
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(
            any(type(other) is type(key) and other == key for other in right)
            and same_content(value, right[key]) for key, value in left.items())
    if isinstance(left, list):
        return len(left) == len(right) and all(same_content(a, b) for a, b in zip(left, right))
    return left == right


def planned(fields, key, root, kind):
    identity(key)
    if fields[key + ".state"] not in ("planned", "published", "moved"):
        raise ValueError("unsupported project publication state")
    before = base64.b64decode(fields[key + ".before"], validate=True)
    after = base64.b64decode(fields[key + ".after"], validate=True)
    if digest(before) != fields[key + ".before-hash"] or digest(after) != fields[key + ".after-hash"]:
        raise ValueError("project payload hash mismatch")
    original, output = project(before), project(after)
    account = identity(fields[key + ".to-account"])
    character = identity(fields[key + ".to-character"])
    if original["id"] != key or output["id"] != key or output["owner-account"] != account or output["owner-character"] != character:
        raise ValueError("project payload full authority differs")
    source, target = safe(root, fields[key + ".source"]), safe(root, fields[key + ".target"])
    if kind == "estates":
        from_account = identity(fields["from-account"])
        from_character = identity(fields["from-character"])
        if fields[key + ".from-account"] != from_account or fields[key + ".from-character"] != from_character or original["owner-account"] != from_account or original["owner-character"] != from_character:
            raise ValueError("source full authority differs")
        captured_decision(fields, key)
        expected_source = root / "character-projects" / from_character / (key + ".yml")
    else:
        from_account = identity(fields["from-account"])
        if original["owner"] != from_account or fields["to-account"] != account or fields["to-character"] != character or account != from_account:
            raise ValueError("legacy fixed recipient/source authority differs")
        expected_source = root / "projects" / from_account / (key + ".yml")
    if source != expected_source.resolve() or target != (root / "character-projects" / character / (key + ".yml")).resolve() or source == target:
        raise ValueError("project path differs from fixed full references")
    expected = dict(original, **{"owner-account": account, "owner-character": character})
    if not same_content(expected, output):
        raise ValueError("ownership plan changes project content or unrelated metadata")
    return source, target, before


def inspect(root, kind, apply=False, backup=None):
    root = Path(root).resolve()
    result = {"journals": [], "unplanned_projects": [], "unassigned_legacy": []}
    recovery = None
    if apply:
        if backup is None:
            raise ValueError("--backup requires a new recovery directory")
        recovery = Path(backup).resolve()
        recovery.mkdir()
        force_ancestors(recovery)
    expected = set()
    files = list((root / "ownership" / kind).glob("*.xml"))
    for journal in sorted(files, key=lambda path: path.stat().st_mtime_ns, reverse=True):
        report = {"journal": str(journal), "records": [], "held": {}}
        try:
            fields = journal_fields(journal)
            if fields.get("format") != "1" or fields.get("kind") != ("estate" if kind == "estates" else "migration"):
                raise ValueError("unsupported journal format/kind")
            if kind == "estates":
                if identity(fields["death"]) != journal.stem:
                    raise ValueError("estate death differs from journal path")
                identity(fields["from-account"]); identity(fields["from-character"])
            report["held"] = {key: value for key, value in fields.items() if key.startswith("held.")}
            ids = sorted(key[:-6] for key in fields if key.endswith(".state") and not key.startswith(("held.", "held-source.")))
            if kind == "migration" and ids and identity(fields["from-account"]) != journal.stem:
                raise ValueError("legacy source account differs from journal path")
            actions = []
            for key in ids:
                source, target, before = planned(fields, key, root, kind)
                expected.add(target)
                row = {"id": key, "state": fields[key + ".state"]}
                source_ok = not source.exists() or digest(source.read_bytes()) == fields[key + ".before-hash"]
                target_ok = not target.exists() or digest(target.read_bytes()) == fields[key + ".after-hash"]
                if not source_ok or not target_ok:
                    row["result"] = "conflict; preserve current files for staff"
                elif not target.exists() and not source.exists():
                    row["result"] = "conflict; both copies missing"
                else:
                    row["result"] = "safe offline rollback"
                    actions.append((source, target, before))
                report["records"].append(row)
            if apply and all(row["result"] == "safe offline rollback" for row in report["records"]):
                archive = root / "ownership" / "rolled-back" / kind / journal.name
                if archive.exists():
                    raise ValueError("rollback archive already exists")
                durable(recovery / journal.relative_to(root), journal.read_bytes())
                # Preserve every current copy before the first restore/removal in this journal.
                for source, target, _ in actions:
                    for path in (source, target):
                        if path.exists():
                            durable(recovery / path.relative_to(root), path.read_bytes())
                for source, target, before in actions:
                    if not source.exists():
                        durable(source, before)
                    if target.exists():
                        target.unlink()
                        force(target.parent)
                for row in report["records"]:
                    row["result"] = "restored"
                durable(archive, journal.read_bytes())
                journal.unlink()
                force(journal.parent)
                report["archived"] = str(archive)
        except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError, yaml.YAMLError) as error:
            report["error"] = str(error)
        result["journals"].append(report)
    for path in sorted((root / "character-projects").glob("*/*.yml")):
        if path.resolve() not in expected:
            result["unplanned_projects"].append(str(path))
    for folder in sorted((root / "projects").iterdir()) if (root / "projects").is_dir() else []:
        if not folder.is_dir() or not (root / "ownership/migration" / (folder.name + ".xml")).exists():
            result["unassigned_legacy"].append(str(folder))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--studio", type=Path, required=True, help="plugins/MusicalInstruments/studio")
    parser.add_argument("--kind", choices=("migration", "estates"), default="migration")
    parser.add_argument("--apply", action="store_true", help="restore checked original records; server must be stopped")
    parser.add_argument("--backup", type=Path, help="new current-state recovery directory; required with --apply")
    args = parser.parse_args()
    print(json.dumps(inspect(args.studio, args.kind, args.apply, args.backup), indent=2))


if __name__ == "__main__":
    main()
