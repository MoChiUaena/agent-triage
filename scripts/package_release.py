#!/usr/bin/env python3
"""Create an allowlisted local demo archive; never include runtime data or credentials."""
import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def project(directory):
    pom = ET.parse(directory / "pom.xml").getroot()
    return pom.findtext("m:artifactId", namespaces=NS), pom.findtext("m:version", namespaces=NS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="target/release")
    parser.add_argument("--allow-dirty", action="store_true", help="Local smoke checks only; record dirty source status in the manifest")
    args = parser.parse_args()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    if dirty and not args.allow_dirty:
        parser.error("Commit the release sources before packaging, or use --allow-dirty for a local smoke check.")
    _, version = project(ROOT)
    if not version or "SNAPSHOT" in version:
        parser.error("Set a release version in the three POMs before building the archive.")
    files = []
    for directory, alias in [(ROOT, "agent-triage"), (ROOT / "sample-service", "order-service"), (ROOT / "inventory-service", "inventory-service")]:
        artifact, child_version = project(directory)
        if child_version != version:
            parser.error("All three projects must use the same release version.")
        jar = directory / "target" / f"{artifact}-{version}.jar"
        if not jar.is_file():
            parser.error(f"Missing built JAR for {artifact}.")
        files.append((jar, f"lib/{alias}.jar"))
    for name in ["start-demo.ps1", "start-demo.sh", "README.txt"]:
        files.append((ROOT / "distribution" / name, name))
    files.append((ROOT / "LICENSE", "LICENSE"))
    manifest = {
        "version": version,
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "sourceTreeDirty": dirty,
        "files": {name: hashlib.sha256(path.read_bytes()).hexdigest() for path, name in files},
        "javaMinimum": 21,
        "initialMode": "DEMO",
        "observationSource": "LIVE",
    }
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"agent-triage-{version}-demo.zip"
    prefix = f"agent-triage-{version}"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for source, name in files:
            bundle.write(source, f"{prefix}/{name}")
        bundle.writestr(f"{prefix}/manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    checksums = f"{digest}  {archive.name}\n"
    demo = ROOT / "docs/assets/agent-triage-demo.mp4"
    if demo.is_file():
        checksums += f"{hashlib.sha256(demo.read_bytes()).hexdigest()}  {demo.name}\n"
    (output / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    print(f"Created {archive.name}: {archive.stat().st_size} bytes; {len(files) + 1} allowlisted entries")


if __name__ == "__main__":
    main()
