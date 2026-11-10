#!/usr/bin/env python3
"""Audit ADR numbering, headers, and local links without changing repository files.

Nonzero in --strict mode when duplicate identifiers or missing targets exist.
Default mode reports existing migration debt without breaking CI.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
ADR_DIR = ROOT / "docs" / "adr"
NUMBER = re.compile(r"^(\d{4})-.*\.md$")
HEADING = re.compile(r"^#\s+ADR[- ]?(\d{4})\b", flags=re.I)
LINK = re.compile(r"\[[^\]]*\]\(([^)#]+\.md)(?:#[^)]*)?\)")


def audit():
    by_number = defaultdict(list)
    errors = []
    for item in sorted(ADR_DIR.glob("*.md")):
        match = NUMBER.match(item.name)
        if match is None:
            continue
        number = match.group(1)
        by_number[number].append(item.relative_to(ROOT).as_posix())
        first_heading = next(
            (line for line in item.read_text(encoding="utf-8").splitlines()
             if line.startswith("# ")), ""
        )
        heading = HEADING.match(first_heading)
        if not heading or heading.group(1) != number:
            errors.append(f"{item.relative_to(ROOT)}: ADR title/filename number mismatch")

    for number, paths in sorted(by_number.items()):
        if len(paths) > 1:
            errors.append(f"Duplicate ADR-{number}: {', '.join(paths)}")

    # Audit every Markdown file for local ADR destination references.
    refs = defaultdict(list)
    for source in sorted((ROOT / "docs").rglob("*.md")):
        for lineno, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            for match in LINK.finditer(line):
                destination = (source.parent / match.group(1)).resolve()
                if ADR_DIR in destination.parents:
                    refs[destination.name].append(f"{source.relative_to(ROOT)}:{lineno}")
                    if not destination.is_file():
                        errors.append(f"{source.relative_to(ROOT)}:{lineno}: missing ADR link {match.group(1)}")

    print(f"ADR documents: {sum(len(v) for v in by_number.values())}")
    for number, paths in sorted(by_number.items()):
        for path in paths:
            print(f"ADR-{number}: {path}; inbound explicit Markdown links: "
                  f"{len(refs.get(Path(path).name, []))}")
    for issue in errors:
        print(f"FINDING: {issue}")
    print(f"Findings: {len(errors)}")
    return len(errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="Fail when ADR debt is found")
    args = parser.parse_args()
    findings = audit()
    return 1 if args.strict and findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
