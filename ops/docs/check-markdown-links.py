#!/usr/bin/env python3
"""Validate local Markdown links without external packages or network access."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"(?<!!)\[[^\]\n]*\]\((<?[^)\n]+>?)")


def destinations(text):
    fence = None
    for line in text.splitlines():
        marker = line.lstrip()[:3]
        if marker in (chr(96) * 3, "~" * 3):
            if fence is None:
                fence = marker
            elif fence == marker:
                fence = None
            continue
        if fence is None:
            for match in LINK.finditer(line):
                yield match.group(1).strip().strip("<>").split(' "')[0]


def inspect(source):
    if not source.is_relative_to(ROOT) or not source.is_file():
        return [f"{source}: missing file or outside repository"]
    problems = []
    for link in destinations(source.read_text(encoding="utf-8")):
        url = urlsplit(link)
        if url.scheme or link.startswith(("#", "//")) or not url.path:
            continue
        target = (source.parent / unquote(url.path)).resolve()
        if not target.is_relative_to(ROOT):
            problems.append(f"{source.relative_to(ROOT)}: unsafe path {link}")
        elif not target.exists():
            problems.append(f"{source.relative_to(ROOT)}: missing target {link}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="Include historical Markdown documents")
    parser.add_argument("paths", nargs="*", help="Paths relative to repository root")
    args = parser.parse_args()
    if args.all and args.paths:
        parser.error("Use --all or explicit paths, not both")
    if args.all:
        files = sorted(ROOT.glob("docs/**/*.md")) + [ROOT / "README.md"]
    elif args.paths:
        files = [(ROOT / p).resolve() for p in args.paths]
    else:
        files = [ROOT / "README.md", ROOT / "docs/README.md"]
        files += sorted((ROOT / "docs").glob("*/README.md"))
        files.append(ROOT / "docs/documentation-standards.md")
    issues = [issue for f in files for issue in inspect(f)]
    for issue in issues:
        print("ERROR:", issue, file=sys.stderr)
    print(f"Checked {len(files)} files; found {len(issues)} invalid relative links")
    return bool(issues)


if __name__ == "__main__":
    raise SystemExit(main())
