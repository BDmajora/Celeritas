<img src="src/main/resources/assets/impetus/textures/gui/icon.png" width="128">

# Impetus

Impetus is a free and open-source performance & shaders mod for Minecraft clients. It is a hard fork of
Celeritas by embeddedt (itself a fork of Embeddium and Oculus 1.7, which in turn descend from the last
FOSS-licensed version of Sodium and from Iris 1.7).

Impetus takes the project in a new direction: closing the feature gap with the modern Sodium renderer,
dynamic translucency sorting, tree-based occlusion culling, GPU driver workarounds, and more, through
independent, original implementations, while keeping first-class support for legacy Minecraft versions and
the bundled shader pipeline. See [SODIUM_PARITY_ROADMAP.md](SODIUM_PARITY_ROADMAP.md) for the roadmap.

**Important note:** There are currently no official Impetus binary releases. If you download a precompiled
Impetus .jar file from any 3rd party source, we cannot provide any support for such files, and you do so at
your own risk. Expect minimal support and many possible bugs due to limited testing.

## Project layout

This repository now targets Forge 1.12.2 directly from the root `src` folder. Shared renderer code lives in
the `:common` project, while Minecraft-specific glue lives under `src/main/java/com/bdmajora/impetus`.

## How to build

Run `./gradlew packageJar`. The resulting jar is written to `build/libs/<impetus version>`.

## How to use

Forge 1.12.2 is supported out of the box on Java 8 + LWJGL 2.

## Shader pack compatibility

The bundled shader pipeline originates from Oculus 1.7 / Iris. Iris-protocol identifiers (such as the
`IRIS_VERSION` define and `iris`-namespaced shader-format identifiers) are intentionally preserved so that
existing shader packs continue to load and function.

## License

Impetus is licensed under the Lesser GNU General Public License version 3, as it only uses code from Iris 1.7,
Sodium 0.5.11-, Celeritas, and other FOSS projects.

Portions of the option screen code are based on Reese's Sodium Options by FlashyReese, and are used under the terms of
the [MIT license](https://opensource.org/license/mit), located in `src/main/resources/licenses/rso.txt`.

This project does not include and has no plans to include any code from Sodium 0.6+ or 0.5.12+, as these versions of
Sodium are not available under a free and open-source license. New features inspired by modern Sodium are
independent, original implementations.

## Credits

* embeddedt, for developing Celeritas and Embeddium, from which Impetus is forked
* The CaffeineMC team, for developing Sodium 0.5.11 & older, and making it open source
* The Iris project and the Oculus port, the origin of the bundled shader pipeline
* Asek3, for developing Rubidium, the original port of Sodium 0.5 to Forge
* CelestialAbyss, for developing the Embeddium logo, and input-Here for some very good visual touchups
* Ven ([@basdxz](https://github.com/basdxz)), for help with translucency sorting, suggesting the general approach for async occlusion culling, and other suggestions during development
* XFactHD, Pepper, and anyone else forgotten to mention, for providing valuable code insights

[![YourKit logo](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)

YourKit supports open source projects with innovative and intelligent tools
for monitoring and profiling Java and .NET applications.
YourKit is the creator of <a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>,
<a href="https://www.yourkit.com/.net/profiler/">YourKit .NET Profiler</a>,
and <a href="https://www.yourkit.com/youmonitor/">YourKit YouMonitor</a>.
