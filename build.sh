#!/usr/bin/env bash

set -e

# Gradle 9+ requires Java 17 or 21 to run the build environment.
# The build still compiles the Forge 1.12.2 mod for Java 8.
if ! java -version 2>&1 | grep -E -q 'version "(17|21)'; then
    echo "Warning: Gradle requires Java 17 or 21 to run."
    echo "Please ensure JDK 21 is set as your default Java environment."
fi

# Ensure the Gradle wrapper can run.
if [ ! -x "./gradlew" ]; then
    chmod +x gradlew
fi

echo "Building Impetus for 1.12.2..."

./gradlew packageJar

echo "Done. The compiled jar is in build/libs/1.0.0-dev/"
