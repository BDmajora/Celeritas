// Iris-style OptiFine shader-pack support for Impetus on Minecraft 1.12.2
//
// Two constraints hold across every class in this package
//
// The build runs Jabel, so modern Java SYNTAX (var, records, switch expressions) compiles down to Java 8 bytecode.
// But this module compiles with --release 8, so Java 9+ LIBRARY APIs (Set.of, List.of, Stream.toList) are not
// available — use the Java 8 equivalents. Only the bytecode-downgraded `common` module may use the newer ones.
//
// All GL access goes through com.bdmajora.impetus.lwjgl.*, never raw org.lwjgl.opengl.*. That abstraction is what
// keeps the same code working on both the LWJGL2 and LWJGL3 backends.
package com.bdmajora.impetus.umbra;
