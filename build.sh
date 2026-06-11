#!/usr/bin/env bash

set -e

# Gradle 9+ requires Java 17 or 21 to run the build environment.
# The Stonecutter toolchain will automatically provision and use Java 8 for the 1.12.2 compilation.
if ! java -version 2>&1 | grep -E -q 'version "(17|21)'; then
    echo "Warning: Gradle requires Java 17 or 21 to run."
    echo "Please ensure JDK 21 is set as your default Java environment."
fi

# Ensure the Gradle wrapper has execute permissions
if [ ! -x "./gradlew" ]; then
    chmod +x gradlew
fi

echo "Building Celeritas for 1.12.2..."

# FIX: Changed -Ptarget_versions to -Pceleritas_target_versions
./gradlew -Pceleritas_target_versions="1.12.2" packageJar

echo "Done. The compiled jar should be located in build/libs/1.12.2/"