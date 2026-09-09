#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

# Gradle 9+ needs a JDK 17/21 to *run*; the mod itself still targets Java 8 via jabel + jvmdowngrader.
check_java() {
    if ! java -version 2>&1 | grep -E -q 'version "(17|21)'; then
        echo "Warning: Gradle requires Java 17 or 21 to run the build environment."
        echo "         Ensure JDK 21 is your default before continuing."
        echo
    fi
}

# The wrapper loses its exec bit on some checkouts (notably fresh Windows clones).
[ -x ./gradlew ] || chmod +x gradlew

version() { grep -E '^project_base_version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]'; }

task_build() {
    ./gradlew packageJar
    echo
    echo "Jar written to build/libs/$(version)/"
}

# Removes every build output, not just the root build/ directory. `gradlew clean` only owns the root
# project's build/, so bin/, run/, the Gradle caches and the buildSrc/common outputs all survive it -
# which is why this is the only clean offered.
task_clean() {
    local dirs=(build bin run .gradle
                common/build common/.gradle
                buildSrc/build buildSrc/.gradle buildSrc/.kotlin)

    if [ "${1:-}" != "--force" ]; then
        echo "Removes: ${dirs[*]}"
        echo "The next build re-decompiles Minecraft and will take several minutes."
        read -r -p "Continue? [y/N] " reply
        case "$reply" in
            [yY]*) ;;
            *) echo "Aborted."; return 0 ;;
        esac
    fi

    rm -rf "${dirs[@]}"
    echo "Done."
}

menu() {
    cat <<'MENU'

  Impetus - Minecraft 1.12.2 (Forge)

    1) Build
    2) Clean
    3) Quit

MENU
}

dispatch() {
    case "$1" in
        1|build) task_build ;;
        2|clean) task_clean "${2-}" ;;
        3|q|quit|exit) return 1 ;;
        *) echo "Unknown option: $1" ;;
    esac
    return 0
}

check_java

# Non-interactive form, e.g. ./run.sh build - keeps the script usable from CI and aliases.
# Such callers have already stated their intent, so clean skips the confirmation there.
if [ $# -gt 0 ]; then
    dispatch "$1" --force
    exit $?
fi

while true; do
    menu
    read -r -p "  Select: " choice
    echo
    dispatch "$choice" || break
done
