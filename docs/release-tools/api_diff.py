#!/usr/bin/env python3
"""List the public and protected JVM API of a released KSLCore jar that the current build no longer has.

A bundle JAR compiled against the release calls the library by these signatures, so anything listed is
a place where such a bundle can fail to load or throw NoSuchMethodError. Members of internal and
synthetic shape (names with '$', `access$`, and the compiler's lambda classes) are listed too but are not
reachable from a model, and a member moved up to a superclass with the same signature is listed but still
resolves; judge each line.

Usage (from the repo root, after ./gradlew :KSLCore:classes):
    python3 -I docs/release-tools/api_diff.py <KSLCore-<release>.jar> KSLCore/build/classes/kotlin/main
"""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def class_names(location: Path) -> list:
    if location.is_file():
        with zipfile.ZipFile(location) as jar:
            names = [n for n in jar.namelist() if n.endswith(".class") and "module-info" not in n]
    else:
        names = [str(p.relative_to(location)) for p in location.rglob("*.class")]
    return [n[:-len(".class")].replace("/", ".") for n in names]


def dump(location: Path) -> dict:
    """Maps each class to the set of its public and protected declarations, as javap prints them."""
    names = class_names(location)
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as listing:
        listing.write("\n".join(names))
    out = subprocess.run(
        f"xargs javap -protected -cp '{location}' < '{listing.name}'",
        shell=True, capture_output=True, text=True, check=False,
    ).stdout
    api, current = {}, None
    for line in out.splitlines():
        if line.startswith("Compiled from"):
            continue
        header = re.match(r"^\S.*?\b(?:class|interface|enum)\s+([\w.$]+)", line)
        if header:
            current = header.group(1)
            api.setdefault(current, set()).add(line.rstrip(" {"))
        elif line.startswith("  ") and current:
            api[current].add(line.strip())
    return api


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    old, new = dump(Path(sys.argv[1])), dump(Path(sys.argv[2]))
    removed_classes = sorted(c for c in old if c not in new)
    changed = [(c, m) for c in sorted(old) if c in new for m in sorted(old[c] - new[c])]
    for c in removed_classes:
        print(f"class removed: {c}")
    for c, m in changed:
        print(f"{c} :: {m}")
    print(f"{len(removed_classes)} classes and {len(changed)} declarations of the release are gone or changed")


if __name__ == "__main__":
    main()
