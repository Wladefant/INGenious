<#
.SYNOPSIS
  Build and install INGenious Playwright Studio from source on a no-admin Windows device.

.DESCRIPTION
  Builds the INGenious source with Maven on JDK 17 (the same two-step build the project's own
  CI uses) and unpacks the resulting setup zip into a versioned folder next to any existing
  install. Nothing is replaced or removed.

  BY DEFAULT IT BUILDS THE CLONE THIS SCRIPT IS IN. That is the point: the Studio has to be
  the one carrying the panel extension point, and the surest way to get that is to build the
  same checkout the plugin and the tools came from. Nothing is fetched and nothing is checked
  out, so a working tree is never moved under anybody.

  Pass -Ref (optionally with -RepoUrl / -SrcDir) to build some OTHER source instead; it then
  clones or updates that checkout and checks the ref out, as it used to.

  A cold build downloads the full Maven dependency set and takes tens of minutes.

.EXAMPLE
  # The normal case: build the clone you are standing in
  powershell -NoProfile -ExecutionPolicy Bypass -File setup-ingenious-laptop.ps1

.EXAMPLE
  # Build a different source tree at a named ref
  ... -File setup-ingenious-laptop.ps1 -SrcDir "$env:USERPROFILE\INGenious-src" -Ref release/3.1.0
#>
[CmdletBinding()]
param(
  # Commit or branch to check out before building. EMPTY (the default) = build what is already
  # in -SrcDir, touching neither the remote nor the working tree.
  [string]$Ref         = '',
  # Only consulted when -Ref is given. Empty = the origin of the checkout in -SrcDir.
  [string]$RepoUrl     = '',
  # The source tree to build. Default: the clone this script lives in.
  [string]$SrcDir      = '',
  # Where the built Studio is unpacked. Default: %USERPROFILE%\ingenious.
  [string]$InstallRoot = '',
  # Folder name created under InstallRoot. Default is filled in from the commit.
  [string]$InstallName = '',
  # Empty = found the way ingenious-launch.ps1 finds it, i.e. the JDK Studio will be started on.
  [string]$JavaHome    = '',
  # Empty = found on PATH, then MAVEN_HOME, then the usual unpacked folders under the profile.
  [string]$Mvn         = '',
  [switch]$RunTests,
  [switch]$Force
)

# 'Continue', deliberately: git/maven/java all write progress and warnings to stderr, which
# under 'Stop' would abort the script on perfectly healthy output. Native steps are checked
# via $LASTEXITCODE instead, and the cmdlets that must not fail silently carry -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

function Log($msg) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg) }
function Fail($msg) { Log "FAILED: $msg"; exit 1 }

# ---------------------------------------------------------------- defaults that are FOUND
# Every default below used to be a literal path from one particular laptop, which meant the
# script failed on its first line on anybody else's machine, for a reason that had nothing to
# do with their machine. Found beats assumed.

if (-not $SrcDir)      { $SrcDir      = (Split-Path -Parent $PSScriptRoot) }
if (-not $InstallRoot) { $InstallRoot = (Join-Path $env:USERPROFILE 'ingenious') }

# The JDK: asked of ingenious-launch.ps1, which is the finder Studio itself is started with,
# so Studio is built on the same JDK it will later run on.
if (-not $JavaHome) {
  $launcher = Join-Path $PSScriptRoot 'ingenious-launch.ps1'
  if (Test-Path -LiteralPath $launcher) {
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher -Check 2>&1 | Out-String
    $m = [regex]::Match($out, 'Java\s*:\s*\S+\s+\((.+?)\)')
    if ($m.Success) { $JavaHome = $m.Groups[1].Value.Trim() }
  }
  if (-not $JavaHome) { Fail 'no JDK 17+ found - run ingenious-launch.ps1 -Check first, or pass -JavaHome' }
  Log "java   : found $JavaHome"
}

# Maven: PATH, then MAVEN_HOME, then the usual unpacked folders. Same order as ing-update.ps1.
if (-not $Mvn) {
  $onPath = Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($onPath) { $Mvn = $onPath.Source }
  if (-not $Mvn -and $env:MAVEN_HOME) {
    $c = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $c) { $Mvn = $c }
  }
  if (-not $Mvn) {
    foreach ($g in @(
        "$env:USERPROFILE\development\apache-maven-*\bin\mvn.cmd",
        "$env:USERPROFILE\development\apache-maven-*\apache-maven-*\bin\mvn.cmd",
        "$env:USERPROFILE\apache-maven-*\bin\mvn.cmd",
        "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd")) {
      $hit = Get-ChildItem -Path $g -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($hit) { $Mvn = $hit.FullName; break }
    }
  }
  if (-not $Mvn) { Fail 'no Maven found on PATH, in MAVEN_HOME or under your profile - pass -Mvn' }
  Log "maven  : found $Mvn"
}

# ---------------------------------------------------------------- preflight
Log "=== INGenious source install ==="
if ($Ref) { Log "ref=$Ref  repo=$RepoUrl" } else { Log "ref=(the checkout as it stands)" }
Log "src=$SrcDir  installRoot=$InstallRoot"

$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $java)) { Fail "java not found at $java" }
if (-not (Test-Path $Mvn))  { Fail "maven not found at $Mvn" }
if (-not (Get-Command git -EA SilentlyContinue)) { Fail 'git not on PATH' }

$env:JAVA_HOME = $JavaHome
Log ("java   : " + ((& $java -version 2>&1) -join ' '))
Log ("maven  : " + ((& $Mvn -v 2>&1 | Select-Object -First 1)))

# ---------------------------------------------------------------- source
if (-not $Ref) {
  # The normal case: build what is checked out and change nothing. No fetch, no checkout, no
  # reset — this is somebody's working tree, and a build must never be able to lose their work.
  if (-not (Test-Path (Join-Path $SrcDir '.git'))) { Fail "$SrcDir is not a git checkout" }
  Push-Location $SrcDir
  if (-not $RepoUrl) {
    $RepoUrl = (& git remote get-url origin 2>$null)
    if ($RepoUrl) { $RepoUrl = $RepoUrl.Trim() } else { $RepoUrl = $SrcDir }
  }
  Log 'building the checkout as it stands (no fetch, no checkout)'
} elseif (Test-Path (Join-Path $SrcDir '.git')) {
  Log "updating existing clone"
  Push-Location $SrcDir
  # An existing clone may point at a different remote than the one requested (e.g. the
  # public mirror vs a fork carrying a feature branch). Re-point it, or the fetch below
  # silently succeeds against the wrong repo and the checkout fails on a missing ref.
  $currentUrl = (& git remote get-url origin 2>$null)
  if ($currentUrl -and $currentUrl.Trim() -ne $RepoUrl) {
    Log "origin is $currentUrl - re-pointing to $RepoUrl"
    & git remote set-url origin $RepoUrl
    if ($LASTEXITCODE) { Fail 'cannot re-point origin' }
  }
  & git fetch --tags --prune origin; if ($LASTEXITCODE) { Fail 'git fetch failed' }
} else {
  Log "cloning $RepoUrl (this pulls the full history once)"
  New-Item -ItemType Directory -Force -Path (Split-Path $SrcDir -Parent) | Out-Null
  & git clone $RepoUrl $SrcDir; if ($LASTEXITCODE) { Fail 'git clone failed' }
  Push-Location $SrcDir
}

# In an existing clone a branch name resolves to the LOCAL branch, and the fetch above
# updates origin/<branch> only -- so -Ref <branch> silently rebuilds whatever was checked
# out last time. Prefer the remote-tracking ref when one exists, so a branch always means
# "the branch head as it is on the server". A commit or tag has no such ref and is used
# as given.
if ($Ref) {
  $checkoutRef = $Ref
  & git rev-parse --verify --quiet "refs/remotes/origin/$Ref" | Out-Null
  if ($LASTEXITCODE -eq 0) {
    $checkoutRef = "origin/$Ref"
    Log "branch ref - checking out $checkoutRef (server head), not the local branch"
  }

  & git -c advice.detachedHead=false checkout --force $checkoutRef
  if ($LASTEXITCODE) { Fail "cannot check out $checkoutRef" }
  & git reset --hard HEAD | Out-Null
  & git clean -fdx -e target -e '*/target' | Out-Null
}

$sha      = (& git rev-parse HEAD).Trim()
$shortSha = $sha.Substring(0, 8)
$describe = (& git describe --tags --always 2>$null)
Log "source at $sha ($describe)"

if (-not $InstallName) { $InstallName = "ingenious-playwright-3.1.0dev-$shortSha" }
$target = Join-Path $InstallRoot $InstallName
if ((Test-Path $target) -and -not $Force) {
  Fail "$target already exists (use -Force to rebuild over it)"
}

# ---------------------------------------------------------------- build
# Same two steps as .github/workflows/maven.yml: ingenious-api first, then the framework.
# prettier is skipped: on Windows its plugin extracts prettier-java into ~/.m2 under a random
# temp name and renames it to prettier-java-<version>, which fails once the first build has
# already created that directory ("Error moving directory ..."). It only reformats source,
# so it has no bearing on the artifact we install.
$testFlag = if ($RunTests) { '-DskipTests=false' } else { '-DskipTests' }
Log "building ingenious-api ($testFlag)"
& $Mvn -B clean install $testFlag '-Dprettier.skip=true' --file (Join-Path $SrcDir 'ingenious-api\pom.xml')
if ($LASTEXITCODE) { Fail 'ingenious-api build failed' }

Log "building framework ($testFlag) - produces Dist/target/*.zip"
& $Mvn -B clean install $testFlag '-Dprettier.skip=true' --file (Join-Path $SrcDir 'pom.xml')
if ($LASTEXITCODE) { Fail 'framework build failed' }

$zip = Get-ChildItem (Join-Path $SrcDir 'Dist\target') -Filter 'ingenious-playwright-*-setup.zip' |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $zip) { Fail 'build produced no setup zip in Dist/target' }
Log ("built {0} ({1} MB)" -f $zip.Name, [math]::Round($zip.Length / 1MB))

# ---------------------------------------------------------------- install
# The zip contains a single root folder (ingenious-playwright-<pom version>); the pom still
# says 3.0.0 on release/3.1.0, so rename it to the commit-stamped name to avoid confusion
# with the existing 3.0.0-preview install.
$staging = Join-Path $env:TEMP ("ingenious-stage-" + $shortSha)
try {
  if (Test-Path $staging) { Remove-Item $staging -Recurse -Force -ErrorAction Stop }
  Log "extracting to staging"
  Expand-Archive -Path $zip.FullName -DestinationPath $staging -Force -ErrorAction Stop

  $root = Get-ChildItem $staging -Directory | Select-Object -First 1
  if (-not $root) { Fail 'unexpected zip layout: no root folder' }

  New-Item -ItemType Directory -Force -Path $InstallRoot -ErrorAction Stop | Out-Null
  if (Test-Path $target) { Log "removing previous $InstallName (-Force)"; Remove-Item $target -Recurse -Force -ErrorAction Stop }
  Move-Item $root.FullName $target -ErrorAction Stop
} catch {
  Fail "install step failed: $($_.Exception.Message)"
}
Remove-Item $staging -Recurse -Force -EA SilentlyContinue

@(
  "source     : $RepoUrl"
  "ref        : $(if ($Ref) { $Ref } else { 'the checkout as it stood' })"
  "commit     : $sha"
  "describe   : $describe"
  "built      : $(Get-Date -Format o)"
  "built with : $((& $Mvn -v 2>&1 | Select-Object -First 1)) / JDK $JavaHome"
  "tests      : $(if ($RunTests) { 'run' } else { 'skipped' })"
) | Set-Content (Join-Path $target 'INSTALL-VERSION.txt') -Encoding UTF8

# ---------------------------------------------------------------- verify
foreach ($f in @('ingenious.bat', 'Engine', 'lib')) {
  if (-not (Test-Path (Join-Path $target $f))) { Fail "install incomplete: missing $f" }
}
Log "OK -> $target"
Log "run with: `"$java`" ... or $target\ingenious.bat"
exit 0
