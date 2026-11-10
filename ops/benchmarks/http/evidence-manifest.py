#!/usr/bin/env python3
"""Create and verify immutable-content benchmark evidence manifests."""

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "1.0.0"

SUFFIXES = (
    "-independent-maven.log",
    "-independent-client.csv",
    "-independent-client.log",
    "-independent-server.csv",
    "-independent-server-jvm.log",
    "-independent-analysis.log",
)

RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^[0-9a-f]{64}$")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def regular_file(path):
    require(not path.is_symlink(), f"Symlink prohibited: {path}")
    require(path.is_file(), f"Missing regular file: {path}")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_directory(path):
    require(path.is_dir() and not path.is_symlink(),
            "Evidence directory must be a real directory")
    return path.resolve(strict=True)


def create(directory, run_id, git_commit):
    directory = artifact_directory(directory)
    require(RUN_ID.fullmatch(run_id) is not None, "Invalid run identifier")
    require(SHA.fullmatch(git_commit) is not None, "Invalid full Git SHA")

    artifacts = []
    for suffix in SUFFIXES:
        name = run_id + suffix
        path = directory / name
        regular_file(path)
        artifacts.append({
            "name": name,
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "benchmark": {
            "name": "independent-process-http",
            "git_commit": git_commit,
            "run_id": run_id,
            "target_rps": 100,
            "duration_seconds": 60,
            "expected_requests": 6000,
        },
        "execution": {
            "client_process": "python",
            "server_process": "java",
            "clock_domains": "independent",
        },
        "artifacts": artifacts,
    }

    destination = directory / (run_id + "-evidence-manifest.json")
    require(not destination.exists() and not destination.is_symlink(),
            "Manifest already exists; refusing to overwrite")

    # Avoid partially written manifests on failure.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=directory,
            prefix=".manifest-",
            suffix=".tmp",
            delete=False,
        ) as stream:
            temporary = Path(stream.name)
            json.dump(manifest, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())

        # Exclusive creation: never silently replace prior evidence.
        os.link(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

    print(f"EVIDENCE_MANIFEST CREATED {destination}")
    verify(destination)


def verify(manifest_path):
    regular_file(manifest_path)
    directory = artifact_directory(manifest_path.parent)

    with manifest_path.open(encoding="utf-8") as stream:
        manifest = json.load(stream)

    require(isinstance(manifest, dict), "Invalid manifest document")
    require(manifest.get("schema_version") == SCHEMA_VERSION,
            "Unsupported schema version")

    benchmark = manifest.get("benchmark")
    require(isinstance(benchmark, dict), "Invalid benchmark metadata")

    run_id = benchmark.get("run_id")
    require(isinstance(run_id, str) and RUN_ID.fullmatch(run_id),
            "Invalid run identifier")
    require(manifest_path.name == run_id + "-evidence-manifest.json",
            "Manifest filename/run identifier mismatch")

    commit = benchmark.get("git_commit")
    require(isinstance(commit, str) and SHA.fullmatch(commit),
            "Invalid Git commit identifier")

    require(benchmark.get("name") == "independent-process-http",
            "Unexpected benchmark type")
    for field, expected in (
        ("target_rps", 100),
        ("duration_seconds", 60),
        ("expected_requests", 6000),
    ):
        value = benchmark.get(field)
        require(type(value) is int and value == expected,
                f"Invalid benchmark setting: {field}")

    require(manifest.get("execution") == {
        "client_process": "python",
        "server_process": "java",
        "clock_domains": "independent",
    }, "Invalid execution metadata")

    artifacts = manifest.get("artifacts")
    require(isinstance(artifacts, list)
            and len(artifacts) == len(SUFFIXES),
            "Invalid artifact inventory")

    expected_names = {run_id + suffix for suffix in SUFFIXES}
    observed_names = set()

    for artifact in artifacts:
        require(isinstance(artifact, dict), "Malformed artifact entry")
        name = artifact.get("name")
        require(isinstance(name, str) and name in expected_names,
                "Unexpected artifact name")
        require(name not in observed_names, "Duplicate artifact entry")
        observed_names.add(name)

        size = artifact.get("bytes")
        digest = artifact.get("sha256")
        require(type(size) is int and size >= 0, "Invalid artifact size")
        require(isinstance(digest, str) and DIGEST.fullmatch(digest),
                "Invalid artifact digest")

        path = directory / name
        regular_file(path)
        require(path.stat().st_size == size,
                f"Size mismatch: {name}")
        require(sha256(path) == digest,
                f"SHA-256 mismatch: {name}")

    require(observed_names == expected_names, "Incomplete artifact inventory")
    print(f"EVIDENCE_MANIFEST VALIDATED artifacts={len(artifacts)} "
          f"run_id={run_id}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    create_parser = sub.add_parser("create")
    create_parser.add_argument("--directory", required=True, type=Path)
    create_parser.add_argument("--run-id", required=True)
    create_parser.add_argument("--git-commit", required=True)

    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("manifest", type=Path)

    args = parser.parse_args()
    try:
        if args.command == "create":
            create(args.directory, args.run_id, args.git_commit)
        else:
            verify(args.manifest)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(1, f"EVIDENCE_MANIFEST INVALID: {exc}\n")


if __name__ == "__main__":
    main()
