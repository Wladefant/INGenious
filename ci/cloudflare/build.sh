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
mkdir -p "$TOOL_DIR/jdk-17.0.20.1+1" "$TOOL_DIR/apache-maven-3.9.9"

if [[ ! -x "$TOOL_DIR/jdk-17.0.20.1+1/bin/java" ]]; then
    jdk_archive="$TOOL_DIR/temurin17.tar.gz"
    curl -fsSL \
        'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz' \
        -o "$jdk_archive"
    printf '%s  %s\n' \
        '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e' \
        "$jdk_archive" | sha256sum --check --status || fail 'Temurin archive checksum failed'
    tar -xzf "$jdk_archive" --strip-components=1 -C "$TOOL_DIR/jdk-17.0.20.1+1"
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

export JAVA_HOME="$TOOL_DIR/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$TOOL_DIR/apache-maven-3.9.9/bin:$PATH"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dfile.encoding=UTF-8"
java -version
mvn --version

xvfb_server_args="-screen 0 1920x1080x24"
if ! command -v xvfb-run >/dev/null 2>&1; then
    command -v apt-get >/dev/null 2>&1 || fail 'xvfb-run is absent and apt-get is unavailable'
    command -v dpkg-deb >/dev/null 2>&1 || fail 'xvfb-run is absent and dpkg-deb is unavailable'
    x11_root="$TOOL_DIR/x11-root"
    apt_root="$TOOL_DIR/apt"
    apt_archives="$apt_root/archives"
    apt_lists="$apt_root/lists"
    mkdir -p "$apt_archives/partial" "$apt_lists/partial"
    apt_config=(
        -o Debug::NoLocking=1
        -o "Dir::Cache::archives=$apt_archives"
        -o "Dir::Cache::pkgcache=$apt_root/pkgcache.bin"
        -o "Dir::Cache::srcpkgcache=$apt_root/srcpkgcache.bin"
        -o "Dir::State::lists=$apt_lists"
    )
    apt-get "${apt_config[@]}" update -qq || fail 'Could not refresh user-space package metadata'
    apt-get "${apt_config[@]}" --no-install-recommends --download-only install -y \
        xvfb xauth xfonts-base fonts-dejavu-core \
        || fail 'Could not download Xvfb and its runtime dependencies'
    shopt -s nullglob
    debs=("$apt_archives"/*.deb)
    ((${#debs[@]} > 0)) || fail 'The user-space Xvfb package download was empty'
    for deb in "${debs[@]}"; do
        dpkg-deb -x "$deb" "$x11_root" || fail "Could not extract package: $deb"
        printf 'CI_X11_PACKAGE name=%s version=%s\n' \
            "$(dpkg-deb -f "$deb" Package)" "$(dpkg-deb -f "$deb" Version)"
    done
    export PATH="$x11_root/usr/bin:$x11_root/usr/sbin:$PATH"
    export LD_LIBRARY_PATH="$x11_root/usr/lib/x86_64-linux-gnu:$x11_root/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
    export XKB_CONFIG_ROOT="$x11_root/usr/share/X11/xkb"
    xvfb_server_args="$xvfb_server_args -fp $x11_root/usr/share/fonts/X11/misc,$x11_root/usr/share/fonts/truetype/dejavu"
fi
command -v xvfb-run >/dev/null 2>&1 || fail 'xvfb-run remains unavailable after setup'
xvfb-run --help >/dev/null
if xvfb-run -a -s "$xvfb_server_args" true; then
    printf 'CI_XVFB_SMOKE passed\n'
else
    status=$?
    fail "Xvfb smoke failed exit=$status"
fi

run_maven() {
    local label="$1"
    local needs_display="$2"
    shift 2
    printf 'CI_COMMAND name=%s display=%s' "$label" "$needs_display"
    printf ' %q' "$@"
    printf '\n'
    local -a runner_command
    if [[ "$needs_display" == "true" ]]; then
        runner_command=(xvfb-run -a -s "$xvfb_server_args" "$@")
    else
        runner_command=("$@")
    fi
    if "${runner_command[@]}"; then
        return 0
    else
        local status=$?
        printf 'CI_ERROR command=%s exit=%s\n' "$label" "$status" >&2
        return "$status"
    fi
}

if ! run_maven ingenious-api false mvn clean install --file ./ingenious-api/pom.xml; then
    python3 ci/cloudflare/report_surefire.py || true
    exit 1
fi

if ! run_maven ingenious-framework true mvn clean install --file pom.xml; then
    python3 ci/cloudflare/report_surefire.py || true
    exit 1
fi

python3 ci/cloudflare/report_surefire.py
