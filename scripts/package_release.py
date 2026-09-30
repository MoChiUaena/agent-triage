#!/usr/bin/env python3
"""Create an allowlisted local demo archive; never include runtime data or credentials."""
import argparse
import hashlib
import json
import subprocess
import shutil
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
        parser.error("Set a release version in the POMs before building the archive.")
    files = []
    for directory, alias in [(ROOT, "agent-triage"), (ROOT / "sample-service", "order-service"),
                             (ROOT / "inventory-service", "inventory-service"), (ROOT / "database-service", "database-service"),
                             (ROOT / "catalog-service", "catalog-service"), (ROOT / "triage-spring-boot-starter", "triage-spring-boot-starter")]:
        artifact, child_version = project(directory)
        if child_version != version:
            parser.error("All packaged projects must use the same release version.")
        jar = directory / "target" / f"{artifact}-{version}.jar"
        if not jar.is_file():
            parser.error(f"Missing built JAR for {artifact}.")
        files.append((jar, f"sdk/{alias}-{version}.jar" if alias == "triage-spring-boot-starter" else f"lib/{alias}.jar"))
    for alias in ("ticket-service", "assignment-service"):
        directory = ROOT / "verification" / alias
        artifact, child_version = project(directory)
        if child_version != version:
            parser.error("Verification applications must match the release version.")
        jar = directory / "target" / f"{artifact}-{version}.jar"
        if not jar.is_file():
            parser.error(f"Missing built JAR for {artifact}.")
        files.append((jar, f"lib/{alias}.jar"))
    for name in ["start-demo.ps1", "start-demo.sh", "README.txt", "SOURCE_DEMO.md"]:
        files.append((ROOT / "distribution" / name, name))
    for relative in ["pom.xml", "src/main/resources/application.yml"] + [f"src/main/java/example/helpdesk/{name}.java" for name in
        ("AssignmentGateway", "DefaultTicketService", "TicketApplication", "TicketController", "TicketFormatter", "TicketService")]:
        files.append((ROOT / "verification/ticket-service" / relative, "projects/ticket-service/" + relative))
    files.append((ROOT / "LICENSE", "LICENSE"))
    files.append((ROOT / "distribution/services.yml", "config/services.yml"))
    files.append((ROOT / "triage-spring-boot-starter/pom.xml", "sdk/pom.xml"))
    starter_name = f"triage-spring-boot-starter-{version}"
    class_agent = ROOT / "triage-spring-boot-starter/target" / f"{starter_name}-agent.jar"
    if not class_agent.is_file():
        parser.error("Missing built runtime class Agent JAR.")
    files.append((class_agent, f"sdk/{starter_name}-agent.jar"))
    manifest = {
        "version": version,
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "sourceTreeDirty": dirty,
        "files": {name: hashlib.sha256(path.read_bytes()).hexdigest() for path, name in files},
        "javaMinimum": 21,
        "initialMode": "DEMO",
        "observationSource": "LIVE",
        "runtimeClassAgent": True,
        "registeredServices": ["order-service", "account-service", "catalog-service", "catalog-db-service", "ticket-service"],
        "portOffsets": {"agent": 0, "order": 2, "inventory": 4, "database": 6, "catalog": 8, "catalogDatabase": 9, "ticket": 10, "assignment": 12},
    }
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"agent-triage-{version}-demo.zip"
    prefix = f"agent-triage-{version}"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for source, name in files:
            bundle.write(source, f"{prefix}/{name}")
        bundle.writestr(f"{prefix}/manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    starter = output / f"{starter_name}.jar"
    starter_pom = output / f"{starter_name}.pom"
    standalone_agent = output / f"{starter_name}-agent.jar"
    shutil.copyfile(ROOT / "triage-spring-boot-starter/target" / starter.name, starter)
    shutil.copyfile(ROOT / "triage-spring-boot-starter/pom.xml", starter_pom)
    shutil.copyfile(class_agent, standalone_agent)
    checksums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
                        for path in (archive, starter, starter_pom, standalone_agent))
    (output / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    print(f"Created {archive.name}: {archive.stat().st_size} bytes; {len(files) + 1} allowlisted entries")
    print(f"Exported {starter.name}, {starter_pom.name}, {standalone_agent.name} and SHA256SUMS.txt")


if __name__ == "__main__":
    main()
