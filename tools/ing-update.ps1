<#
.SYNOPSIS
  Bring every part of the ING tester setup to the current state, in one command, and say what
  it is at afterwards.

.DESCRIPTION
  Three things drift apart independently on a tester machine, and on 2026-07-28 they did:

    1. this repository  - the Node tools the Studio panels start as child processes
    2. the built Studio - built from THIS checkout (or, in the older internal layout, from
                          whatever the `INGenious` submodule pinned)
    3. the plugin JAR   - inside that Studio install

  The plugin had been rebuilt and redeployed many times; the checkout beside it was a month
  old and five of the tools were simply absent. Nothing said so. A tester pressed a button,
  got a greyed control, and reasonably concluded the feature was broken.

  This script updates all three and then REPORTS ALL THREE, whether or not it changed them -
  so "I ran the update" and "I know what I am on" are the same act. It fails loudly and with
  a non-zero exit code on anything it cannot do; the one failure mode it will not have is the
  silent one.

  It reuses rather than reimplements:
    * ingenious-launch.ps1 -Check   finds the JDK 17 and the install (it runs `java -version`
                                    on every candidate rather than trusting a folder name)
    * setup-ingenious-laptop.ps1    builds and installs the Studio, when the pin has moved

  No admin rights, no .exe, nothing installed system-wide, nothing written outside the
  repository, the install folder and Maven's own cache.

  ASCII ONLY, DELIBERATELY, AND DO NOT "FIX" THE GERMAN BACK.
  Windows PowerShell 5.1 reads a .ps1 without a byte-order mark as ANSI. The third byte of a
  UTF-8 em dash is 0x94, which CP1252 decodes to a closing curly quote - and 5.1 accepts curly
  quotes as string delimiters. One em dash inside a double-quoted string therefore ends the
  string early and the whole file stops parsing, with errors pointing two hundred lines away
  from the cause. Measured on this file. ingenious-launch.ps1 and setup-ingenious-laptop.ps1
  are ASCII for the same reason; umlauts are written ae/oe/ue, as they are there.

.PARAMETER Repo
  The checkout to update. Default: the parent of the folder this script is in.

.PARAMETER Install
  The INGenious install to update the plugin inside. Default: whatever
  `ingenious-launch.ps1 -Check` reports, which is the same install the launcher starts.

.PARAMETER Mvn
  Path to mvn.cmd. Default: PATH, then MAVEN_HOME, then an unpacked Maven under the profile.

.PARAMETER SkipStudio
  Do not rebuild the Studio even when the required commit has moved. The move is still
  REPORTED, and the exit code is non-zero - a skipped step is never silent.

.PARAMETER Check
  Report the state of all three parts and change nothing. Exit 0 = everything is current.

.EXAMPLE
  tools\ing-update.cmd

.EXAMPLE
  # "what am I on?" - changes nothing
  tools\ing-update.cmd -Check

.NOTES
  Exit codes: 0 everything current / 1 something failed / 2 a prerequisite is missing
  / 3 something is out of date and was not updated (only with -SkipStudio or -Check).
#>
[CmdletBinding()]
param(
  [string]$Repo,
  [string]$Install,
  [string]$Mvn,
  [switch]$SkipStudio,
  [switch]$Check
)

# 'Continue', deliberately, for the reason setup-ingenious-laptop.ps1 records: git, maven and
# java all write progress and warnings to stderr, which under 'Stop' aborts the script on
# perfectly healthy output. Native steps are gated on $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$script:Failed = @()
$script:Stale  = @()

function Step($m) { Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function Info($m) { Write-Host "   $m" }
function Good($m) { Write-Host "   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "   $m" -ForegroundColor Yellow }
function Bad ($m) { Write-Host "   $m" -ForegroundColor Red }
function Fail($what, $m) { Bad $m; $script:Failed += "$what : $m" }

# ---------------------------------------------------------------- where things are

if (-not $Repo) { $Repo = Split-Path -Parent $PSScriptRoot }
# Recognised by shape rather than by any one file, and that is the same rule the panel's own
# check uses: a checkout that has gone stale can be MISSING the file you recognise it by, and
# then "your checkout is old" comes out as "you have no checkout", which is a different
# instruction to a different person.
if (-not (Test-Path -LiteralPath (Join-Path $Repo 'tools')) -or
    -not (Test-Path -LiteralPath (Join-Path $Repo 'ingenious-plugin'))) {
  Bad "Das ist kein Arbeitsverzeichnis dieses Projekts: $Repo"
  Bad "Not a checkout of this project (expected a tools\ and an ingenious-plugin\ folder)."
  exit 2
}
$Repo = (Resolve-Path -LiteralPath $Repo).Path

Write-Host ''
Write-Host '================================================================'
Write-Host " ING Tester-Setup aktualisieren   $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
Write-Host '================================================================'
Info "Arbeitsverzeichnis : $Repo"
if ($Check) { Warn 'Nur pruefen (-Check): es wird nichts veraendert.' }

# ---------------------------------------------------------------- prerequisites

Step 'Voraussetzungen'

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  Bad 'git ist auf diesem Rechner nicht vorhanden. Ohne git gibt es kein Update.'
  Bad 'git is not on this machine. Without it there is no update.'
  exit 2
}
Info ("git   : " + ((& git --version) -join ' '))

$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
  Info ("node  : " + ((& node --version) -join ' ') + "   (" + $node.Source + ")")
} else {
  # Not fatal for the update itself, but every tool this script updates is a Node script, so
  # a machine without node has an update that cannot be used. Said now rather than discovered
  # later at a button.
  Warn 'node ist NICHT vorhanden. Die Werkzeuge werden aktualisiert, sind aber nicht startbar.'
  $script:Stale += 'node fehlt auf diesem Rechner'
}

# The JDK and the install, from the launcher - one finder, used by the launcher and by this
# script, so the two can never disagree about which install a tester is running.
$launcher = Join-Path $PSScriptRoot 'ingenious-launch.ps1'
if (-not (Test-Path -LiteralPath $launcher)) {
  Bad "Der Starter fehlt: $launcher"
  exit 2
}
$checkArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launcher, '-Check')
if ($Install) { $checkArgs += @('-Install', $Install) }
$checkOut = & powershell @checkArgs 2>&1
$checkRc  = $LASTEXITCODE
$checkOut | ForEach-Object { Info $_ }
if ($checkRc -ne 0) {
  Bad 'Der Starter kann INGenious auf diesem Rechner nicht starten (siehe oben).'
  Bad 'ingenious-launch.ps1 -Check failed; nothing was changed.'
  exit 2
}
$javaHome   = ''
$installDir = ''
$m = $checkOut | Select-String -Pattern '^Java\s+:\s+\S+\s+\((.+)\)\s*$'
if ($m) { $javaHome = $m.Matches[0].Groups[1].Value }
$m = $checkOut | Select-String -Pattern '^INGenious\s+:\s+(.+?)\s*$'
if ($m) { $installDir = $m.Matches[0].Groups[1].Value }
if (-not $javaHome -or -not $installDir) {
  Bad 'Die Ausgabe des Starters war nicht lesbar - Java- oder Installationspfad fehlt.'
  exit 2
}

# Maven, only needed when something has to be built.
function Resolve-Mvn {
  if ($Mvn) { if (Test-Path -LiteralPath $Mvn) { return $Mvn } else { return $null } }
  $onPath = Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($onPath) { return $onPath.Source }
  if ($env:MAVEN_HOME) {
    $c = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $c) { return $c }
  }
  # Bounded globs, NOT -Recurse. The first version walked the whole development folder looking
  # for mvn.cmd and turned a status check into a seven-minute wait - measured, on a machine
  # that has no Maven at all, which is the case where it walks everything before giving up.
  # A one-command update that takes seven minutes to say "nothing to do" is one nobody runs.
  foreach ($glob in @(
      "$env:USERPROFILE\development\apache-maven-*\bin\mvn.cmd",
      "$env:USERPROFILE\development\apache-maven-*\apache-maven-*\bin\mvn.cmd",
      "$env:USERPROFILE\apache-maven-*\bin\mvn.cmd",
      "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd")) {
    $hit = Get-Item -Path $glob -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { return $hit.FullName }
  }
  $null
}
$mvnPath = Resolve-Mvn
if ($mvnPath) { Info ("maven : " + $mvnPath) } else { Info 'maven : NICHT gefunden' }

# ---------------------------------------------------------------- 1. the repository

Step '1 von 3 - Werkzeuge (dieses Arbeitsverzeichnis)'

$branch  = (& git -C $Repo rev-parse --abbrev-ref HEAD).Trim()
$before  = (& git -C $Repo rev-parse HEAD).Trim()
Info "Zweig  : $branch"
Info ("vorher : " + $before.Substring(0,8))

$upstream = (& git -C $Repo rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $upstream) {
  # A branch with no upstream cannot be pulled, and `git pull` says so in a sentence that
  # reads like a configuration hint rather than like "your update did nothing".
  Fail 'repo' ("Der Zweig " + $branch + " folgt keinem Zweig auf dem Server. Es kann nichts geholt werden.")
} elseif ($Check) {
  & git -C $Repo fetch --quiet 2>$null | Out-Null
  $behind = (& git -C $Repo rev-list --count ("HEAD.." + $upstream.Trim()) 2>$null)
  if ($LASTEXITCODE -eq 0 -and $behind -and [int]$behind -gt 0) {
    Warn "$behind Aenderung(en) liegen bereit und sind NICHT geholt (Zweig $upstream)."
    $script:Stale += "$behind Aenderung(en) im Arbeitsverzeichnis nicht geholt"
  } else {
    Good "Aktuell gegenueber $upstream."
  }
} else {
  $dirty = (& git -C $Repo status --porcelain)
  if ($dirty) {
    # Refuse rather than stash: a stash on somebody else's machine is a thing they will not
    # find again, and this script must never be the reason work disappears.
    Fail 'repo' 'Es liegen eigene Aenderungen im Arbeitsverzeichnis. Bitte erst sichern; es wurde nichts geholt.'
    $dirty | Select-Object -First 10 | ForEach-Object { Info "   $_" }
  } else {
    Info "hole   : $upstream"
    & git -C $Repo pull --ff-only
    if ($LASTEXITCODE) {
      Fail 'repo' 'git pull --ff-only ist fehlgeschlagen (siehe oben). Es wurde nichts geaendert.'
    }
  }
}

$after = (& git -C $Repo rev-parse HEAD).Trim()
if ($after -ne $before) {
  Good ("neu    : " + $after.Substring(0,8))
  # WHICH tools moved, by name. This is the sentence that would have made the 2026-07-28
  # incident a non-event: five tool files appeared upstream and nobody's machine said so.
  $changed = @(& git -C $Repo diff --name-only $before $after -- tools ing-qa-recorder/mvp)
  if ($changed.Count) {
    Info ("geaenderte Werkzeuge (" + $changed.Count + "):")
    $changed | ForEach-Object { Info "   $_" }
  } else {
    Info 'An den Werkzeugen selbst hat sich nichts geaendert.'
  }
} elseif (-not $Check) {
  Good 'Die Werkzeuge waren bereits aktuell.'
}

# The submodules carry the Studio source; without this the pin below is read from a gitlink
# whose commit is not on the machine.
if (Test-Path -LiteralPath (Join-Path $Repo '.gitmodules')) {
  if ($Check) {
    Info 'Untermodule werden bei -Check nicht angefasst.'
  } else {
    & git -C $Repo submodule update --init --recursive 2>&1 | ForEach-Object { Info $_ }
    if ($LASTEXITCODE) { Fail 'submodule' 'git submodule update ist fehlgeschlagen (siehe oben).' }
  }
}

# ---------------------------------------------------------------- 2. the Studio

Step '2 von 3 - Studio (INGenious)'

# Which Studio commit this checkout asks for. Two layouts, and the difference matters:
#
#   * THE FORK (what a colleague clones): the Studio source IS this checkout - the panel
#     extension point Studio needs and the plugin that uses it are in the same tree. The
#     required commit is therefore simply this checkout's HEAD, and a build must build HERE.
#     Building anything else produces a Studio without the extension point, and then the
#     `Ablauf` button never appears - with nothing on screen to say why.
#   * THE INTERNAL REPOSITORY: the Studio lives in an `INGenious` submodule, and the required
#     commit is the gitlink. Kept working, because that checkout still exists.
$pinned = ''
$studioSrc = $Repo
$line = (& git -C $Repo ls-tree HEAD INGenious) -join ''
if ($line -match '^\d+\s+commit\s+([0-9a-f]{40})\s') {
  $pinned = $Matches[1]
  $studioSrc = Join-Path $Repo 'INGenious'
} elseif (Test-Path -LiteralPath (Join-Path $Repo 'ingenious-api')) {
  # No submodule and an ingenious-api module beside us: this checkout is the Studio source.
  $head = (& git -C $Repo rev-parse HEAD 2>$null)
  if ($LASTEXITCODE -eq 0 -and $head) { $pinned = $head.Trim() }
}

$installedCommit = ''
$versionFile = Join-Path $installDir 'INSTALL-VERSION.txt'
if (Test-Path -LiteralPath $versionFile) {
  $m = Select-String -LiteralPath $versionFile -Pattern '^commit\s*:\s*([0-9a-f]{40})'
  if ($m) { $installedCommit = $m.Matches[0].Groups[1].Value }
}
Info "Installation : $installDir"
if ($installedCommit) { Info ("installiert  : " + $installedCommit.Substring(0,8)) }
else                  { Info 'installiert  : unbekannt (keine INSTALL-VERSION.txt)' }
if ($pinned) { Info ("verlangt     : " + $pinned.Substring(0,8)) }
else         { Info 'verlangt     : unbekannt (kein Studio-Quelltext gefunden)' }

$studioNeedsBuild = $false
if (-not $pinned) {
  Warn 'Es laesst sich nicht sagen, welcher Studio-Stand verlangt ist (weder ein INGenious-Untermodul noch ein Studio-Quelltext in diesem Arbeitsverzeichnis).'
} elseif (-not $installedCommit) {
  # An install with no receipt: judged unknown, not judged wrong. A folder name is not a
  # version - the branch's pom still says 3.0.0 for every one of these builds.
  Warn 'Diese Installation traegt keine Herkunftsangabe. Ihr Stand ist nicht feststellbar.'
  $script:Stale += 'Studio-Installation ohne INSTALL-VERSION.txt'
} elseif ($installedCommit -eq $pinned) {
  Good 'Studio ist auf dem verlangten Stand.'
} else {
  Warn 'Studio ist NICHT auf dem verlangten Stand.'
  $studioNeedsBuild = $true
}

if ($studioNeedsBuild -and ($Check -or $SkipStudio)) {
  $script:Stale += ("Studio steht auf " + $installedCommit.Substring(0,8) + ", verlangt ist " + $pinned.Substring(0,8))
  Warn 'Es wurde nichts gebaut (-Check bzw. -SkipStudio).'
} elseif ($studioNeedsBuild) {
  if (-not $mvnPath) {
    Fail 'studio' 'Zum Bauen wird Maven gebraucht und es wurde keins gefunden. Bitte mit -Mvn angeben.'
  } else {
    $setup = Join-Path $PSScriptRoot 'setup-ingenious-laptop.ps1'
    if (-not (Test-Path -LiteralPath $setup)) {
      Fail 'studio' 'Das Bau-Skript setup-ingenious-laptop.ps1 fehlt.'
    } else {
      $installRoot = Split-Path -Parent $installDir
      $setupArgs = @('-SrcDir', $studioSrc, '-InstallRoot', $installRoot,
                     '-JavaHome', $javaHome, '-Mvn', $mvnPath, '-Force')
      if ($studioSrc -eq $Repo) {
        # This checkout IS the source. Build it as it stands: no fetch and no checkout, so a
        # build can never move somebody's working tree or lose an uncommitted change.
        Info ("baue Studio aus diesem Arbeitsverzeichnis @ " + $pinned.Substring(0,8) + " - das dauert einige Minuten.")
      } else {
        # The submodule says both which repository and which commit. Read rather than repeated:
        # a URL typed twice is a URL that goes out of step, and a build against the wrong remote
        # is the single easiest way to produce an install that looks right and is a day old.
        $url = (& git -C $Repo config -f .gitmodules submodule.INGenious.url)
        if ($url) { $url = $url.Trim() }
        if (-not $url) { $url = (& git -C $studioSrc remote get-url origin 2>$null); if ($url) { $url = $url.Trim() } }
        Info ("baue Studio aus " + $url + " @ " + $pinned.Substring(0,8) + " - das dauert einige Minuten.")
        $setupArgs += @('-RepoUrl', $url, '-Ref', $pinned)
      }
      & powershell -NoProfile -ExecutionPolicy Bypass -File $setup @setupArgs
      if ($LASTEXITCODE) {
        Fail 'studio' 'Der Studio-Bau ist fehlgeschlagen (siehe oben).'
      } else {
        Good 'Studio neu gebaut.'
        # The new install is a NEW folder, named after the commit. Point the rest of this run
        # at it, or the plugin would be installed beside the old one.
        $fresh = Get-ChildItem -LiteralPath $installRoot -Directory -ErrorAction SilentlyContinue |
                 Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'ingenious.bat') } |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($fresh) { $installDir = $fresh.FullName; Info "Installation jetzt: $installDir" }
        Warn 'Die Startdatei muss auf diese neue Installation zeigen - bitte pruefen.'
      }
    }
  }
}

# ---------------------------------------------------------------- 3. the plugin

Step '3 von 3 - Panel-Plugin (die Knoepfe in Studio)'

$pluginDir = Join-Path $installDir 'plugins\ing-tester-panel'
$jarName   = 'ing-tester-panel-0.1.0.jar'
$installedJar = Join-Path $pluginDir $jarName

function Get-JarStamp([string]$jar) {
  if (-not (Test-Path -LiteralPath $jar)) { return '' }
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
      $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
      if (-not $entry) { return '' }
      $reader = New-Object System.IO.StreamReader($entry.Open())
      try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
      if ($text -match 'Ing-Qa-Repo-Commit:\s*(\S+)') { return $Matches[1] }
      return ''
    } finally { $zip.Dispose() }
  } catch { return '' }
}

$oldStamp = Get-JarStamp $installedJar
if (Test-Path -LiteralPath $installedJar) { Info ("eingebaut : " + $installedJar) }
else                                      { Info 'eingebaut : noch keins' }
if ($oldStamp) { Info ("gebaut aus: " + $oldStamp) } else { Info 'gebaut aus: unbekannt' }

# WHAT THE BUILD IS ALLOWED TO CLAIM ABOUT ITSELF, and why the two refusals matter.
#
# The panel warns "your checkout does not contain the commit this plugin was built from". For
# that sentence to be true it has to be impossible for the commit to be missing for any other
# reason. So a real commit id is stamped ONLY when the tree was clean (otherwise the JAR is
# not that commit) AND the commit is already on a remote (otherwise a checkout can be right up
# to date and still not have it). In every other case a WORD goes in, the panel recognises it
# is not a hex id, and it says nothing at all. Silence beats a warning nobody can act on.
if (& git -C $Repo status --porcelain) {
  $stamp = 'dirty'
} else {
  $head = (& git -C $Repo rev-parse HEAD).Trim()
  $onRemote = @(& git -C $Repo branch -r --contains $head 2>$null)
  if ($LASTEXITCODE -eq 0 -and $onRemote.Count) { $stamp = $head } else { $stamp = 'unpublished' }
}
Info "Stempel   : $stamp"

if ($Check) {
  if ($oldStamp -and $stamp -match '^[0-9a-f]{40}$' -and $oldStamp -ne $stamp) {
    Warn 'Das eingebaute Plugin stammt aus einem anderen Stand als dieses Arbeitsverzeichnis.'
    $script:Stale += 'Plugin-JAR stammt aus einem anderen Stand'
  } elseif (Test-Path -LiteralPath $installedJar) {
    Good 'Ein Plugin ist eingebaut.'
  } else {
    Warn 'Es ist gar kein Plugin eingebaut - die Knoepfe erscheinen in Studio nicht.'
    $script:Stale += 'kein Plugin-JAR in der Installation'
  }
} elseif (-not $mvnPath) {
  Fail 'plugin' 'Zum Bauen des Plugins wird Maven gebraucht und es wurde keins gefunden. Bitte mit -Mvn angeben.'
} else {
  $pluginSrc = Join-Path $Repo 'ingenious-plugin'
  Info 'baue Plugin...'
  $env:JAVA_HOME = $javaHome
  & $mvnPath -B -q package -DskipTests ("-Ding.qa.commit=" + $stamp) --file (Join-Path $pluginSrc 'pom.xml')
  if ($LASTEXITCODE) {
    Fail 'plugin' 'Der Plugin-Bau ist fehlgeschlagen (siehe oben). Das alte Plugin bleibt unveraendert.'
  } else {
    $built = Join-Path $pluginSrc ('target\' + $jarName)
    if (-not (Test-Path -LiteralPath $built)) {
      Fail 'plugin' ("Der Bau lief durch, aber " + $jarName + " ist nicht entstanden.")
    } else {
      New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null
      Copy-Item -LiteralPath $built -Destination $installedJar -Force
      # Read the stamp back OUT OF THE INSTALLED FILE rather than trusting the variable: what
      # the panel will read is the manifest of the JAR that is actually lying there.
      $newStamp = Get-JarStamp $installedJar
      if (-not $newStamp) {
        Fail 'plugin' 'Das eingebaute Plugin traegt keine Herkunftsangabe - die Veraltet-Warnung kann dann nichts sagen.'
      } elseif ($newStamp -ne $stamp) {
        Fail 'plugin' ("Das eingebaute Plugin traegt " + $newStamp + ", gebaut wurde mit " + $stamp + ".")
      } else {
        Good ("Plugin eingebaut: " + $installedJar)
      }
    }
  }
}

# ---------------------------------------------------------------- the verification

Step 'Kontrolle - sind die Werkzeuge wirklich da?'

# The same five files de.ing.qa.panel.RepoCheck watches, and for the same reason: this is the
# list whose absence made a button grey with no explanation. Checked HERE as well, because an
# update that leaves one of them missing has to say so at the moment it happens, not the next
# time somebody opens Studio.
$required = @(
  @{ p = 'tools\selector-uniqueness.mjs';      f = 'Aufnahme pruefen' },
  @{ p = 'tools\handoff-pack.mjs';             f = 'Aufnahme abgeben' },
  @{ p = 'tools\ado-testcases.mjs';            f = 'Testfaelle aus Azure DevOps holen' },
  @{ p = 'tools\parse-report.mjs';             f = 'Testergebnis nach Azure DevOps melden' },
  @{ p = 'ing-qa-recorder\mvp\ado-upload.mjs'; f = 'Testergebnis nach Azure DevOps melden' }
)
$missing = @()
foreach ($r in $required) {
  if (Test-Path -LiteralPath (Join-Path $Repo $r.p)) {
    Info ("  vorhanden : " + $r.p)
  } else {
    Bad  ("  FEHLT     : " + $r.p + "   -> " + $r.f)
    $missing += $r.p
  }
}
if ($missing.Count) {
  Fail 'werkzeuge' ($missing.Count.ToString() + " von " + $required.Count + " Werkzeugen fehlen auch NACH dem Update.")
} else {
  Good ("Alle " + $required.Count + " Werkzeuge sind vorhanden.")
}

# ---------------------------------------------------------------- the report

Write-Host ''
Write-Host '================================================================'
Write-Host ' Stand nach diesem Lauf'
Write-Host '================================================================'
$finalStamp = Get-JarStamp (Join-Path $installDir ('plugins\ing-tester-panel\' + $jarName))
$finalHead  = (& git -C $Repo rev-parse HEAD).Trim()
$finalDesc  = (& git -C $Repo log -1 --date=short --format=%h%x20%ad%x20%s).Trim()
$finalInstalledCommit = ''
$vf = Join-Path $installDir 'INSTALL-VERSION.txt'
if (Test-Path -LiteralPath $vf) {
  $m = Select-String -LiteralPath $vf -Pattern '^commit\s*:\s*([0-9a-f]{40})'
  if ($m) { $finalInstalledCommit = $m.Matches[0].Groups[1].Value }
}
Write-Host ("  Werkzeuge : " + $finalHead.Substring(0,8) + "  (" + $branch + ")")
Write-Host ("              " + $finalDesc)
Write-Host ("  Studio    : " + $installDir)
if ($finalInstalledCommit) { Write-Host ("              " + $finalInstalledCommit.Substring(0,8)) }
else                       { Write-Host '              Herkunft unbekannt' }
if ($finalStamp) { Write-Host ("  Plugin    : " + $finalStamp) }
else             { Write-Host '  Plugin    : kein JAR oder ohne Herkunftsangabe' }
Write-Host ("  Java      : " + $javaHome)
Write-Host ''

if ($script:Failed.Count) {
  Write-Host 'FEHLGESCHLAGEN / FAILED:' -ForegroundColor Red
  $script:Failed | ForEach-Object { Write-Host "  * $_" -ForegroundColor Red }
  Write-Host ''
  Write-Host 'Es ist NICHT alles aktuell. Bitte diese Ausgabe an die Testautomatisierung geben.'
  exit 1
}
if ($script:Stale.Count) {
  Write-Host 'NICHT AKTUELL / NOT CURRENT:' -ForegroundColor Yellow
  $script:Stale | ForEach-Object { Write-Host "  * $_" -ForegroundColor Yellow }
  Write-Host ''
  Write-Host 'Nichts ist fehlgeschlagen, aber es ist auch nicht alles aktuell.'
  exit 3
}
Write-Host 'Alles aktuell. Bitte Studio einmal neu starten.' -ForegroundColor Green
Write-Host 'Everything is current. Restart Studio once.' -ForegroundColor Green
exit 0
