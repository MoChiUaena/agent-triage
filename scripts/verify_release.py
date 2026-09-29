#!/usr/bin/env python3
"""Verify a downloaded demo, standalone starter and their checksums without running any JAR."""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from package_release import NS, ROOT, project


def stream_digest(source):
    value = hashlib.sha256()
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        value.update(chunk)
    return value.hexdigest()


def digest(path):
    with path.open("rb") as source:
        return stream_digest(source)


def verify(directory, version, commit=None):
    prefix = f"agent-triage-{version}/"
    starter_name = f"triage-spring-boot-starter-{version}"
    assets = {f"agent-triage-{version}-demo.zip", f"{starter_name}.jar", f"{starter_name}.pom"}
    entries = {}
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]) or fields[1] not in assets or fields[1] in entries:
            raise ValueError("Invalid or unexpected checksum entry")
        entries[fields[1]] = fields[0]
    if set(entries) != assets:
        raise ValueError("Checksums must cover exactly the demo ZIP, starter JAR and starter POM")
    for name, expected in entries.items():
        if digest(directory / name) != expected:
            raise ValueError(f"Checksum mismatch: {name}")
    allowed = {"lib/agent-triage.jar", "lib/order-service.jar", "lib/inventory-service.jar", "lib/database-service.jar",
               "lib/catalog-service.jar", f"sdk/{starter_name}.jar", "sdk/pom.xml", "config/services.yml",
               "start-demo.ps1", "start-demo.sh", "README.txt", "LICENSE"}
    with zipfile.ZipFile(directory / f"agent-triage-{version}-demo.zip") as archive:
        names = archive.namelist()
        if len(names) != 13 or set(names) != {prefix + name for name in allowed | {"manifest.json"}}:
            raise ValueError("Archive contains missing, duplicate or unexpected paths")
        manifest = json.loads(archive.read(prefix + "manifest.json"))
        if manifest.get("version") != version or manifest.get("sourceTreeDirty") is not False:
            raise ValueError("Release manifest must have the expected version and clean source")
        source_commit = manifest.get("sourceCommit", "")
        if not re.fullmatch(r"[0-9a-f]{40}", source_commit) or commit is not None and source_commit != commit:
            raise ValueError("Release source commit does not match")
        if set(manifest.get("files", {})) != allowed:
            raise ValueError("Manifest file allowlist does not match")
        for name, expected in manifest["files"].items():
            with archive.open(prefix + name) as source:
                if stream_digest(source) != expected:
                    raise ValueError(f"Embedded checksum mismatch: {name}")
        if manifest["files"][f"sdk/{starter_name}.jar"] != entries[f"{starter_name}.jar"] \
                or manifest["files"]["sdk/pom.xml"] != entries[f"{starter_name}.pom"]:
            raise ValueError("Standalone starter differs from the bundled starter")
    pom = ET.parse(directory / f"{starter_name}.pom").getroot()
    coordinates = [pom.findtext(f"m:{name}", namespaces=NS) for name in ("groupId", "artifactId", "version")]
    if coordinates != ["io.github.mochiuaena", "triage-spring-boot-starter", version]:
        raise ValueError("Starter Maven coordinates do not match")
    with zipfile.ZipFile(directory / f"{starter_name}.jar") as starter:
        if "io/github/mochiuaena/triage/sdk/TriageObservationAutoConfiguration.class" not in starter.namelist() \
                or any(name.startswith("BOOT-INF/") for name in starter.namelist()):
            raise ValueError("Starter must be a plain library JAR with its auto-configuration")
    print(f"Release verified: 3 checksummed assets, 13 ZIP entries, matching starter; source={source_commit}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "target/release")
    parser.add_argument("--version", default=project(ROOT)[1])
    parser.add_argument("--source-commit", help="Full SHA of the release tag for comparison with manifest.json")
    args = parser.parse_args()
    verify(args.directory, args.version, args.source_commit)


if __name__ == "__main__":
    main()
