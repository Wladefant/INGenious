#!/usr/bin/env bash
set -uo pipefail

fail() {
    printf 'CI_ERROR %s\n' "$1" >&2
    exit 1
}


for tool in curl tar sha256sum sha512sum python3; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required tool is missing: $tool"
done
TOOL_DIR="${RUNNER_TEMP:-/tmp}/ingenious-ci-tools"
mkdir -p "$TOOL_DIR/jdk-17.0.14+7" "$TOOL_DIR/apache-maven-3.9.9"

if [[ ! -x "$TOOL_DIR/jdk-17.0.14+7/bin/java" ]]; then
    jdk_archive="$TOOL_DIR/temurin17.tar.gz"
    curl -fsSL \
        'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.14%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.14_7.tar.gz' \
        -o "$jdk_archive"
    printf '%s  %s\n' \
        'a3af83983fb94dd7d11b13ba2dba0fb6819dc2caaf87e6937afd22ad4680ae9a' \
        "$jdk_archive" | sha256sum --check --status || fail 'Temurin archive checksum failed'
    tar -xzf "$jdk_archive" --strip-components=1 -C "$TOOL_DIR/jdk-17.0.14+7"
    rm "$jdk_archive"
fi

if [[ ! -x "$TOOL_DIR/apache-maven-3.9.9/bin/mvn" ]]; then
    maven_archive="$TOOL_DIR/apache-maven-3.9.9.tar.gz"
    curl -fsSL \
        'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz' \
        -o "$maven_archive"
    printf '%s  %s\n' \
        'a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b' \
        "$maven_archive" | sha512sum --check --status || fail 'Maven archive checksum failed'
    tar -xzf "$maven_archive" --strip-components=1 -C "$TOOL_DIR/apache-maven-3.9.9"
    rm "$maven_archive"
fi

export JAVA_HOME="$TOOL_DIR/jdk-17.0.14+7"
export PATH="$JAVA_HOME/bin:$TOOL_DIR/apache-maven-3.9.9/bin:$PATH"
java -version
mvn --version

run_maven() {
    local label="$1"
    shift
    printf 'CI_COMMAND name=%s' "$label"
    printf ' %q' "$@"
    printf '\n'
    if "$@"; then
        return 0
    else
        local status=$?
        printf 'CI_ERROR command=%s exit=%s\n' "$label" "$status" >&2
        return "$status"
    fi
}

if ! run_maven ingenious-api mvn clean install --file ./ingenious-api/pom.xml; then
    python3 ci/cloudflare/report_surefire.py || true
    exit 1
fi

if ! run_maven ingenious-framework mvn clean install --file pom.xml; then
    python3 ci/cloudflare/report_surefire.py || true
    exit 1
fi

python3 ci/cloudflare/report_surefire.py
