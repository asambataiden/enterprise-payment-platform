#!/usr/bin/env python3
"""Contract and adversarial tests for benchmark evidence manifests."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
TOOL = HERE / "evidence-manifest.py"

RUN_ID = "20261110T192732Z-27136"
COMMIT = "a" * 40

SUFFIXES = (
    "-independent-maven.log",
    "-independent-client.csv",
    "-independent-client.log",
    "-independent-server.csv",
    "-independent-server-jvm.log",
    "-independent-analysis.log",
)


class EvidenceManifestTests(unittest.TestCase):

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)

        self.directory = Path(self.temporary.name)
        self.manifest = (
            self.directory / f"{RUN_ID}-evidence-manifest.json"
        )

        for suffix in SUFFIXES:
            artifact = self.directory / (RUN_ID + suffix)
            artifact.write_bytes(
                f"synthetic fixture: {suffix}\n".encode("utf-8")
            )

    def command(self, *args, expect_success=True):
        result = subprocess.run(
            [sys.executable, str(TOOL), *map(str, args)],
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )

        if expect_success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("EVIDENCE_MANIFEST INVALID", result.stderr)

        return result

    def create(self):
        return self.command(
            "create",
            "--directory", self.directory,
            "--run-id", RUN_ID,
            "--git-commit", COMMIT,
        )

    def verify(self, expect_success=True):
        return self.command(
            "verify",
            self.manifest,
            expect_success=expect_success,
        )

    def load_manifest(self):
        return json.loads(self.manifest.read_text(encoding="utf-8"))

    def save_manifest(self, data):
        self.manifest.write_text(
            json.dumps(data, indent=2) + "\n",
            encoding="utf-8",
        )

    def test_create_and_verify(self):
        self.create()
        self.assertTrue(self.manifest.is_file())
        self.verify()

    def test_manifest_contains_all_artifacts(self):
        self.create()
        data = self.load_manifest()

        self.assertEqual(data["schema_version"], "1.0.0")
        self.assertEqual(len(data["artifacts"]), 6)

        for entry in data["artifacts"]:
            path = self.directory / entry["name"]
            self.assertEqual(entry["bytes"], path.stat().st_size)
            self.assertEqual(
                entry["sha256"],
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )

    def test_refuses_manifest_overwrite(self):
        self.create()
        before = self.manifest.read_bytes()

        self.command(
            "create",
            "--directory", self.directory,
            "--run-id", RUN_ID,
            "--git-commit", COMMIT,
            expect_success=False,
        )

        self.assertEqual(self.manifest.read_bytes(), before)

    def test_rejects_missing_artifact_at_creation(self):
        (self.directory / (RUN_ID + SUFFIXES[0])).unlink()

        self.command(
            "create",
            "--directory", self.directory,
            "--run-id", RUN_ID,
            "--git-commit", COMMIT,
            expect_success=False,
        )

        self.assertFalse(self.manifest.exists())

    def test_rejects_missing_artifact_at_verification(self):
        self.create()
        (self.directory / (RUN_ID + SUFFIXES[0])).unlink()
        self.verify(expect_success=False)

    def test_rejects_modified_artifact_content(self):
        self.create()
        path = self.directory / (RUN_ID + SUFFIXES[1])
        original = path.read_bytes()

        # Change content without changing artifact size.
        path.write_bytes(b"X" + original[1:])
        self.assertEqual(path.stat().st_size, len(original))

        result = self.verify(expect_success=False)
        self.assertIn("SHA-256 mismatch", result.stderr)

    def test_rejects_truncated_artifact(self):
        self.create()
        path = self.directory / (RUN_ID + SUFFIXES[1])
        path.write_bytes(b"truncated")

        result = self.verify(expect_success=False)
        self.assertIn("Size mismatch", result.stderr)

    def test_rejects_duplicate_artifact(self):
        self.create()
        data = self.load_manifest()
        data["artifacts"][1] = dict(data["artifacts"][0])
        self.save_manifest(data)

        self.verify(expect_success=False)

    def test_rejects_path_traversal(self):
        self.create()
        data = self.load_manifest()
        data["artifacts"][0]["name"] = "../outside.log"
        self.save_manifest(data)

        self.verify(expect_success=False)

    def test_rejects_invalid_schema_version(self):
        self.create()
        data = self.load_manifest()
        data["schema_version"] = "999.0.0"
        self.save_manifest(data)

        self.verify(expect_success=False)

    def test_rejects_invalid_commit(self):
        self.create()
        data = self.load_manifest()
        data["benchmark"]["git_commit"] = "invalid-sha"
        self.save_manifest(data)

        self.verify(expect_success=False)

    def test_rejects_invalid_execution_metadata(self):
        self.create()
        data = self.load_manifest()
        data["execution"]["clock_domains"] = "shared"
        self.save_manifest(data)

        self.verify(expect_success=False)

    def test_rejects_boolean_as_integer_setting(self):
        self.create()
        data = self.load_manifest()
        data["benchmark"]["target_rps"] = True
        self.save_manifest(data)

        self.verify(expect_success=False)

    def test_rejects_malformed_json(self):
        self.create()
        self.manifest.write_text("{invalid json", encoding="utf-8")
        self.verify(expect_success=False)

    def test_rejects_symlinked_artifact(self):
        self.create()
        artifact = self.directory / (RUN_ID + SUFFIXES[0])
        target = self.directory / "replacement.log"

        target.write_bytes(artifact.read_bytes())
        artifact.unlink()
        artifact.symlink_to(target)

        self.verify(expect_success=False)

    def test_rejects_symlinked_manifest(self):
        self.create()
        actual = self.directory / "original-manifest.json"
        self.manifest.rename(actual)
        self.manifest.symlink_to(actual)

        self.verify(expect_success=False)

    def test_manifest_serialization_is_deterministic(self):
        self.create()
        first = self.manifest.read_bytes()

        # Reproduce the same fixture in a different directory.
        with tempfile.TemporaryDirectory() as other:
            other_dir = Path(other)

            for suffix in SUFFIXES:
                source = self.directory / (RUN_ID + suffix)
                (other_dir / source.name).write_bytes(
                    source.read_bytes()
                )

            self.command(
                "create",
                "--directory", other_dir,
                "--run-id", RUN_ID,
                "--git-commit", COMMIT,
            )

            second = (
                other_dir / self.manifest.name
            ).read_bytes()

            self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main(verbosity=2)
