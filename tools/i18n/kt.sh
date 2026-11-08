#!/bin/sh
# Compiles buildSrc LumenI18n + Extract.kt with the Kotlin compiler shipped in Gradle and writes keys.json.
# Usage (repo root): tools/i18n/kt.sh [skipped.txt]
set -e
L=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
CP=$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-*.jar $L/kotlin-script-runtime-*.jar $L/kotlin-reflect-*.jar $L/trove4j-*.jar $L/kotlinx-coroutines-core-jvm-*.jar $L/annotations-*.jar | tr '\n' ':')
OUT=${TMPDIR:-/tmp}/lumen_i18n_kt
mkdir -p "$OUT"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -d "$OUT" -cp $L/kotlin-stdlib-2.0.21.jar \
  buildSrc/src/main/kotlin/LumenI18n.kt tools/i18n/Extract.kt 2>&1 | grep -v "^warning" || true
java -cp "$OUT:$(ls $L/kotlin-stdlib-*.jar)" ExtractKt . "${1:-/dev/null}"
