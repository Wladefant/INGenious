#!/usr/bin/env bash
set -uo pipefail

fail() {
    printf 'CI_ERROR %s\n' "$1" >&2
    exit 1
}


for tool in curl tar sha256sum sha512sum python3 ldd grep; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required tool is missing: $tool"
done
TOOL_DIR="${HOME:-/opt/buildhome}/.cache/ingenious-ci-tools"
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


[[ "$(uname -m)" == "x86_64" ]] || fail 'The pinned PRoot binary requires x86_64'
for tool in apt-get dpkg-deb; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required X11 package tool is missing: $tool"
done

proot_path="$TOOL_DIR/proot-5.5.0"
if [[ ! -x "$proot_path" ]]; then
    proot_archive="$TOOL_DIR/proot-5.5.0"
    curl -fsSL \
        'https://github.com/proot-me/proot/releases/download/v5.5.0/proot' \
        -o "$proot_archive"
    printf '%s  %s\n' \
        'db185300a9daabbce86aca7632d8c7fdc4249f6f153171954013b70c5f491ec0' \
        "$proot_archive" | sha256sum --check --status || fail 'PRoot archive checksum failed'
    chmod 755 "$proot_archive"
fi
"$proot_path" --version

x11_root="$TOOL_DIR/x11-root"
apt_root="$TOOL_DIR/apt"
apt_archives="$apt_root/archives"
apt_lists="$apt_root/lists"
mkdir -p "$x11_root" "$apt_archives/partial" "$apt_lists/partial"
apt_config=(
    -o Debug::NoLocking=1
    -o "Dir::Cache::archives=$apt_archives"
    -o "Dir::Cache::pkgcache=$apt_root/pkgcache.bin"
    -o "Dir::Cache::srcpkgcache=$apt_root/srcpkgcache.bin"
    -o "Dir::State::lists=$apt_lists"
)
apt-get "${apt_config[@]}" update -qq || fail 'Could not refresh user-space X11 package metadata'
apt-get "${apt_config[@]}" --no-install-recommends --download-only install -y \
    xvfb xauth x11-xkb-utils xfonts-base fonts-dejavu-core \
    libxtst6 libxi6 libxrender1 libxext6 libx11-6 libxfixes3 libxcursor1 \
    libxrandr2 libxinerama1 libxss1 libfontconfig1 libfreetype6 libasound2t64 \
    libxau6 libxdmcp6 libxcb1 libpng16-16 \
    || fail 'Could not download user-space Xvfb runtime packages'
shopt -s nullglob
debs=("$apt_archives"/*.deb)
((${#debs[@]} > 0)) || fail 'The user-space X11 package download was empty'
for deb in "${debs[@]}"; do
    dpkg-deb -x "$deb" "$x11_root" || fail "Could not extract package: $deb"
    printf 'CI_X11_PACKAGE name=%s version=%s\n' \
        "$(dpkg-deb -f "$deb" Package)" "$(dpkg-deb -f "$deb" Version)"
done
shopt -u nullglob

xvfb_run="$x11_root/usr/bin/xvfb-run"
xkbcomp="$x11_root/usr/bin/xkbcomp"
xkb_root="$x11_root/usr/share/X11/xkb"
[[ -x "$xvfb_run" ]] || fail 'The extracted xvfb-run command is unavailable'
[[ -x "$xkbcomp" ]] || fail 'The extracted xkbcomp command is unavailable'
[[ -d "$xkb_root" ]] || fail 'The extracted XKB keymap data is unavailable'
export PATH="$x11_root/usr/bin:$x11_root/usr/sbin:$PATH"
export LD_LIBRARY_PATH="$JAVA_HOME/lib/server:$x11_root/usr/lib/x86_64-linux-gnu:$x11_root/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export XKB_CONFIG_ROOT="$xkb_root"
awt_missing="$(ldd "$JAVA_HOME/lib/libawt_xawt.so" "$JAVA_HOME/lib/libfontmanager.so" "$JAVA_HOME/lib/libawt.so" | grep 'not found' || true)"
if [[ -n "$awt_missing" ]]; then
    printf 'CI_AWT_LINKAGE_ERROR_BEGIN\n%s\nCI_AWT_LINKAGE_ERROR_END\n' "$awt_missing" >&2
    fail 'Java AWT native runtime libraries are unresolved'
fi
printf 'CI_AWT_LINKAGE=resolved\n'
xvfb_server_args="-screen 0 1920x1080x24 -fp $x11_root/usr/share/fonts/X11/misc,$x11_root/usr/share/fonts/truetype/dejavu -xkbdir $xkb_root"
xvfb_tmp="$TOOL_DIR/tmp"
mkdir -p "$xvfb_tmp/.X11-unix" || fail 'Could not create a user-owned X11 socket directory'
chmod 1777 "$xvfb_tmp/.X11-unix" || fail 'Could not prepare the user-owned X11 socket directory'


run_xvfb() {
    local xvfb_log="$1"
    shift
    local status
    if "$proot_path" -b "$xvfb_tmp:/tmp" -b "$xkbcomp:/usr/bin/xkbcomp" \
        /usr/bin/env \
        PATH="$PATH" \
        LD_LIBRARY_PATH="$LD_LIBRARY_PATH" \
        XKB_CONFIG_ROOT="$XKB_CONFIG_ROOT" \
        /bin/bash -c 'exec "$@"' bash "$xvfb_run" -a -e "$xvfb_log" -s "$xvfb_server_args" "$@"; then
        return 0
    else
        status=$?
    fi
    if [[ -s "$xvfb_log" ]]; then
        printf 'CI_XVFB_ERROR_BEGIN\n' >&2
        cat "$xvfb_log" >&2
        printf 'CI_XVFB_ERROR_END\n' >&2
    fi
    printf 'CI_ERROR xvfb_exit=%s\n' "$status" >&2
    return "$status"
}

if run_xvfb "$TOOL_DIR/xvfb-smoke.log" true; then
    printf 'CI_XVFB_SMOKE passed\n'
else
    fail 'Rootless Xvfb smoke failed'
fi


run_maven() {
    local label="$1"
    local needs_display="$2"
    shift 2
    local status
    if [[ "$needs_display" == "true" ]]; then
        printf 'CI_COMMAND name=%s display=xvfb' "$label"
        printf ' %q' "$@"
        printf '\n'
        if run_xvfb "$TOOL_DIR/xvfb-${label}.log" "$@"; then
            return 0
        else
            status=$?
        fi
    else
        printf 'CI_COMMAND name=%s display=none' "$label"
        printf ' %q' "$@"
        printf '\n'
        if "$@"; then
            return 0
        else
            status=$?
        fi
    fi
    printf 'CI_ERROR command=%s exit=%s\n' "$label" "$status" >&2
    return "$status"
}

if ! run_maven ingenious-api false mvn clean install --file ./ingenious-api/pom.xml; then
    python3 ci/cloudflare/report_surefire.py || true
    exit 1
fi

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dtestfx.robot=glass -Dtestfx.headless=true -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw"
printf 'CI_JAVA_TOOL_OPTIONS=testfx.robot=glass,testfx.headless=true,glass.platform=Monocle,monocle.platform=Headless,prism.order=sw\n'
export MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }-Pci-headless"
printf 'CI_MAVEN_PROFILE=ci-headless\n'
if ! run_maven ingenious-framework true mvn clean install --file pom.xml; then
    python3 ci/cloudflare/report_surefire.py || true
    exit 1
fi

python3 ci/cloudflare/report_surefire.py
