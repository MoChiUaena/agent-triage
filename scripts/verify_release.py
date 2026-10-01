#!/usr/bin/env python3
"""Verify a downloaded demo, Starter, optional Java Agent and checksums without running any JAR."""
import argparse
import hashlib
import io
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


def version_number(value):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise ValueError("Release version must have three numeric components")
    return tuple(int(part) for part in value.split("."))


def verify_jar_coordinates(jar, group, artifact, version):
    properties = jar.read(f"META-INF/maven/{group}/{artifact}/pom.properties").decode("utf-8")
    values = dict(line.split("=", 1) for line in properties.splitlines() if line and not line.startswith("#"))
    if [values.get(name) for name in ("groupId", "artifactId", "version")] != [group, artifact, version]:
        raise ValueError(f"JAR Maven coordinates do not match: {artifact}")


def verify(directory, version, commit=None):
    number = version_number(version)
    prefix = f"agent-triage-{version}/"
    starter_name = f"triage-spring-boot-starter-{version}"
    agent_name = f"{starter_name}-agent.jar"
    assets = {f"agent-triage-{version}-demo.zip", f"{starter_name}.jar", f"{starter_name}.pom"}
    entries = {}
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]) or fields[1] not in (assets | {agent_name}) or fields[1] in entries:
            raise ValueError("Invalid or unexpected checksum entry")
        entries[fields[1]] = fields[0]
    has_agent = agent_name in entries
    if number >= (0, 5, 0) and not has_agent:
        raise ValueError("The runtime class Agent JAR is required from v0.5")
    expected_assets = assets | ({agent_name} if has_agent else set())
    if set(entries) != expected_assets:
        raise ValueError("Checksums do not cover the required release attachments")
    for name, expected in entries.items():
        if digest(directory / name) != expected:
            raise ValueError(f"Checksum mismatch: {name}")
    allowed = {"lib/agent-triage.jar", "lib/order-service.jar", "lib/inventory-service.jar", "lib/database-service.jar",
               "lib/catalog-service.jar", f"sdk/{starter_name}.jar", "sdk/pom.xml", "config/services.yml",
               "start-demo.ps1", "start-demo.sh", "README.txt", "LICENSE"}
    if has_agent:
        allowed.add(f"sdk/{agent_name}")
    if number >= (0, 10, 0):
        allowed.add("OBSERVATIONS.md")
    if number >= (0, 3, 0):
        allowed |= {"lib/ticket-service.jar", "lib/assignment-service.jar", "SOURCE_DEMO.md", "projects/ticket-service/pom.xml", "projects/ticket-service/src/main/resources/application.yml"}
        allowed |= {f"projects/ticket-service/src/main/java/example/helpdesk/{name}.java" for name in
                    ("AssignmentGateway", "DefaultTicketService", "TicketApplication", "TicketController", "TicketFormatter", "TicketService")}
    with zipfile.ZipFile(directory / f"agent-triage-{version}-demo.zip") as archive:
        names = archive.namelist()
        if len(names) != len(allowed) + 1 or set(names) != {prefix + name for name in allowed | {"manifest.json"}}:
            raise ValueError("Archive contains missing, duplicate or unexpected paths")
        manifest = json.loads(archive.read(prefix + "manifest.json"))
        if manifest.get("version") != version or manifest.get("sourceTreeDirty") is not False:
            raise ValueError("Release manifest must have the expected version and clean source")
        if manifest.get("runtimeClassAgent") is not (True if has_agent else None):
            raise ValueError("Release manifest and Agent attachment differ")
        source_commit = manifest.get("sourceCommit", "")
        if not re.fullmatch(r"[0-9a-f]{40}", source_commit) or commit is not None and source_commit != commit:
            raise ValueError("Release source commit does not match")
        if set(manifest.get("files", {})) != allowed:
            raise ValueError("Manifest file allowlist does not match")
        for name, expected in manifest["files"].items():
            with archive.open(prefix + name) as source:
                if stream_digest(source) != expected:
                    raise ValueError(f"Embedded checksum mismatch: {name}")
        if number >= (0, 10, 0):
            modules = {"agent-triage": ("io.github.mochiuaena", "agent-triage"),
                "order-service": ("io.github.mochiuaena", "triage-sample-service"),
                "inventory-service": ("io.github.mochiuaena", "triage-inventory-service"),
                "database-service": ("io.github.mochiuaena", "triage-database-service"),
                "catalog-service": ("io.github.mochiuaena", "triage-catalog-service"),
                "ticket-service": ("example.helpdesk", "ticket-service"), "assignment-service": ("example.helpdesk", "assignment-service")}
            for alias, (group, artifact) in modules.items():
                with zipfile.ZipFile(io.BytesIO(archive.read(prefix + f"lib/{alias}.jar"))) as jar:
                    verify_jar_coordinates(jar, group, artifact, version)
        if number >= (0, 3, 0):
            if manifest.get("registeredServices") != ["order-service", "account-service", "catalog-service", "catalog-db-service", "ticket-service"] \
                    or manifest.get("portOffsets") != {"agent":0,"order":2,"inventory":4,"database":6,"catalog":8,"catalogDatabase":9,"ticket":10,"assignment":12}:
                raise ValueError("Bundle service and port manifest does not match")
            with zipfile.ZipFile(io.BytesIO(archive.read(prefix + "lib/ticket-service.jar"))) as ticket:
                properties = ticket.read("META-INF/triage/source-digests-v1.properties").decode("utf-8")
                rows = dict(line.split("=", 1) for line in properties.splitlines() if line and not line.startswith("#"))
                if rows.get("format") != "1":
                    raise ValueError("Missing ticket build source manifest")
                for name in ("AssignmentGateway", "DefaultTicketService", "TicketApplication", "TicketController", "TicketFormatter", "TicketService"):
                    value = rows.get("example.helpdesk." + name, "")
                    if not re.fullmatch(r"[a-f0-9]{64},[a-f0-9]{64}", value):
                        raise ValueError("Invalid bundled build source digest")
                    source = archive.read(prefix + f"projects/ticket-service/src/main/java/example/helpdesk/{name}.java")
                    compiled = ticket.read(f"BOOT-INF/classes/example/helpdesk/{name}.class")
                    if value != hashlib.sha256(source).hexdigest() + "," + hashlib.sha256(compiled).hexdigest():
                        raise ValueError("Bundled ticket source and runtime class manifest differ")
        if manifest["files"][f"sdk/{starter_name}.jar"] != entries[f"{starter_name}.jar"] \
                or manifest["files"]["sdk/pom.xml"] != entries[f"{starter_name}.pom"]:
            raise ValueError("Standalone starter differs from the bundled starter")
        if has_agent and manifest["files"][f"sdk/{agent_name}"] != entries[agent_name]:
            raise ValueError("Standalone runtime class Agent differs from the bundled JAR")
    pom = ET.parse(directory / f"{starter_name}.pom").getroot()
    coordinates = [pom.findtext(f"m:{name}", namespaces=NS) for name in ("groupId", "artifactId", "version")]
    if coordinates != ["io.github.mochiuaena", "triage-spring-boot-starter", version]:
        raise ValueError("Starter Maven coordinates do not match")
    with zipfile.ZipFile(directory / f"{starter_name}.jar") as starter:
        if "io/github/mochiuaena/triage/sdk/TriageObservationAutoConfiguration.class" not in starter.namelist() \
                or any(name.startswith("BOOT-INF/") for name in starter.namelist()):
            raise ValueError("Starter must be a plain library JAR with its auto-configuration")
        if number >= (0, 10, 0):
            verify_jar_coordinates(starter, "io.github.mochiuaena", "triage-spring-boot-starter", version)
            if not {"io/github/mochiuaena/triage/sdk/TriageObservationContext.class",
                "io/github/mochiuaena/triage/sdk/TriageObservationContext$Snapshot.class",
                "io/github/mochiuaena/triage/sdk/TriageCallableContext.class"} <= set(starter.namelist()):
                raise ValueError("Starter is missing the v0.10 context propagation classes")
    if has_agent:
        with zipfile.ZipFile(directory / agent_name) as runtime_agent:
            classes = [name for name in runtime_agent.namelist() if name.endswith(".class")]
            manifest_text = runtime_agent.read("META-INF/MANIFEST.MF").decode("utf-8")
            if classes != ["io/github/mochiuaena/triage/sdk/RuntimeClassAgent.class"] \
                    or "Premain-Class: io.github.mochiuaena.triage.sdk.RuntimeClassAgent" not in manifest_text:
                raise ValueError("Runtime class Agent must contain only its premain class")
    print(f"Release verified: {len(expected_assets)} checksummed assets, {len(allowed) + 1} ZIP entries, matching starter; source={source_commit}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "target/release")
    parser.add_argument("--version", default=project(ROOT)[1])
    parser.add_argument("--source-commit", help="Full SHA of the release tag for comparison with manifest.json")
    args = parser.parse_args()
    verify(args.directory, args.version, args.source_commit)


if __name__ == "__main__":
    main()
