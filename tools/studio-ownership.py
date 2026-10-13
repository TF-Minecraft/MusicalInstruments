#!/usr/bin/env python3
"""Inspect studio ownership journals and preview/apply an offline guarded rollback."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET


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


def inspect(root, kind, apply=False):
    result = {"journals": [], "unplanned_projects": [], "unassigned_legacy": []}
    expected = set()
    files = list((root / "ownership" / kind).glob("*.xml"))
    for journal in sorted(files, key=lambda path: path.stat().st_mtime_ns, reverse=True):
        report = {"journal": str(journal), "records": [], "held": {}}
        try:
            fields = {entry.attrib["key"]: entry.text or "" for entry in ET.parse(journal).getroot().findall("entry")}
            if fields.get("format") != "1":
                raise ValueError("unsupported journal format")
            report["held"] = {key: value for key, value in fields.items() if key.startswith("held.")}
            ids = sorted(key[:-6] for key in fields if key.endswith(".state")
                         and not key.startswith(("held.", "held-source.")))
            for identity in ids:
                source = safe(root, fields[identity + ".source"])
                target = safe(root, fields[identity + ".target"])
                expected.add(target)
                before = base64.b64decode(fields[identity + ".before"], validate=True)
                if digest(before) != fields[identity + ".before-hash"]:
                    raise ValueError("source backup hash mismatch")
                row = {"id": identity, "state": fields[identity + ".state"]}
                source_ok = not source.exists() or digest(source.read_bytes()) == fields[identity + ".before-hash"]
                target_ok = not target.exists() or digest(target.read_bytes()) == fields[identity + ".after-hash"]
                if not source_ok or not target_ok:
                    row["result"] = "conflict; preserve current files for staff"
                elif not target.exists() and not source.exists():
                    row["result"] = "conflict; both copies missing"
                else:
                    row["result"] = "safe offline rollback"
                    if apply:
                        if not source.exists():
                            durable(source, before)
                        if target.exists():
                            target.unlink()
                            force(target.parent)
                        row["result"] = "restored"
                report["records"].append(row)
            # Keep receipt available while any conflicting publication needs staff recovery.
            if apply and all(row["result"] == "restored" for row in report["records"]):
                archive = root / "ownership" / "rolled-back" / kind / journal.name
                if archive.exists():
                    raise ValueError("rollback archive already exists")
                archive.parent.mkdir(parents=True, exist_ok=True)
                os.rename(journal, archive)
                force_ancestors(archive.parent)
                force(journal.parent)
                report["archived"] = str(archive)
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
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
    args = parser.parse_args()
    print(json.dumps(inspect(args.studio.resolve(), args.kind, args.apply), indent=2))


if __name__ == "__main__":
    main()
