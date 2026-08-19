#!/usr/bin/env python3
# Verifies that every @Shadow member in a mixin actually exists on the class it targets.
#
# Why this exists: a @Shadow naming a field that is not present on the target compiles perfectly
# happily. The mixin class simply declares a field of its own; nothing resolves it against the
# target until Mixin applies the config at runtime, at which point the game dies during startup.
# The reobfuscator does not catch it either, because an unmatched name is indistinguishable from a
# mixin's own private field, so it silently passes the name through unmapped.
#
# That is exactly how `ResourceLocation.resourceDomain`/`resourcePath` shipped: correct in the
# OptiFine decompile, but this project builds against mcp_stable/39 where those fields are named
# `namespace` and `path`. Compiled clean, crashed on launch.
#
# Usage:  tools/shadowcheck.py [package-prefix]
# Run after a build; exits non-zero and prints every unresolved member.

import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASSES = os.path.join(REPO, "build", "classes", "java", "main")

# MCP-named Minecraft plus Forge, i.e. the names the sources are written against, plus the engine
# classes from :common — some mixins target Impetus' own classes rather than Minecraft's.
CLASSPATH_CANDIDATES = [
    os.path.join(REPO, "build", "rfg", "recompiled_minecraft-1.12.2.jar"),
    os.path.join(REPO, "common", "build", "classes", "java", "main"),
] + [
    os.path.expanduser(p)
    for p in (
        "~/.gradle/caches/minecraft/net/minecraftforge/forge/1.12.2-14.23.5.2847/unpacked/classes.jar",
    )
]

MIXIN_TARGET = re.compile(r"value=\[(.*?)\]", re.S)
CLASS_REF = re.compile(r"class L([\w/$]+);")


def javap(classpath, name, verbose=False):
    cmd = ["javap", "-p"] + (["-v"] if verbose else []) + ["-cp", classpath, name]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def mixin_classes(prefix):
    root = os.path.join(CLASSES, prefix.replace(".", os.sep))
    for dirpath, _, filenames in os.walk(root):
        for filename in filenames:
            if not filename.endswith(".class"):
                continue
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, CLASSES)
            yield rel[: -len(".class")].replace(os.sep, ".")


def parse_mixin(dump):
    """Returns (targets, shadowed_members) for one mixin class dump."""
    targets = []

    # The class-level @Mixin annotation is the first one naming org.spongepowered...Mixin(
    for match in re.finditer(r"org\.spongepowered\.asm\.mixin\.Mixin\(\s*value=\[(.*?)\]", dump, re.S):
        targets = CLASS_REF.findall(match.group(1))
        break

    # Members carrying @Shadow. javap emits each member followed by its annotations, so walk the
    # dump linearly and remember the most recent member signature.
    shadowed = []
    current = None
    for line in dump.splitlines():
        stripped = line.strip()
        member = re.match(r"^(?:[\w.<>\[\]$?, ]+\s)?([\w$]+)(\(.*\))?;$", stripped)
        if member and not stripped.startswith("descriptor:"):
            current = member.group(1)
        if "org.spongepowered.asm.mixin.Shadow" in stripped and current:
            shadowed.append(current)
            current = None

    return targets, shadowed


def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "com.bdmajora.coartatio.mixin"

    classpath = os.pathsep.join(p for p in CLASSPATH_CANDIDATES if os.path.exists(p))
    if not classpath:
        print("error: no Minecraft/Forge dev jars found; run a build first", file=sys.stderr)
        return 2
    full = classpath + os.pathsep + CLASSES

    failures = []
    checked = 0

    for name in sorted(mixin_classes(prefix)):
        dump = javap(CLASSES, name, verbose=True)
        if dump is None:
            continue

        targets, shadowed = parse_mixin(dump)
        if not targets or not shadowed:
            continue

        for target in targets:
            target_name = target.replace("/", ".")
            target_dump = javap(full, target_name)
            if target_dump is None:
                failures.append(f"{name}: cannot load target {target_name}")
                continue

            for member in shadowed:
                checked += 1
                # Word-boundary match so `path` does not accidentally satisfy `resourcePath`.
                if not re.search(r"\b" + re.escape(member) + r"\b", target_dump):
                    failures.append(
                        f"{name}: @Shadow '{member}' does not exist on {target_name}"
                    )

    for failure in failures:
        print("FAIL " + failure)

    print(f"\nchecked {checked} shadowed member(s); {len(failures)} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
