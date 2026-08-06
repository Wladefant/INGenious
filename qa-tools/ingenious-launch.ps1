<#
.SYNOPSIS
  Start INGenious Playwright Studio, or run the INGenious CLI, on a JDK 17+ that is
  *found* rather than assumed.

.DESCRIPTION
  Managed corporate devices often ship Java 1.8 on PATH, and users cannot reorder PATH
  without admin rights. INGenious' own `ingenious.bat` invokes bare `java` / `javaw`, so it picks up 1.8
  and dies with

      UnsupportedClassVersionError ... class file version 61.0

  which tells a tester nothing. This launcher looks for a JDK 17 or newer, checks it by
  actually asking it for its version, pins it for the child process only, and then hands
  over to the install's own `ingenious.bat`.

  Nothing outside this script changes: no system or user PATH edit, no registry write, no
  admin rights. JAVA_HOME and PATH are set in this process, which the launched INGenious
  inherits and nothing else does.

  It delegates to `ingenious.bat` on purpose rather than rebuilding the java command line:
  INGenious 3.0 routes both GUI and CLI to `com.ing.ide.main.Main`, while 3.1 routes the
  CLI to `com.ing.engine.core.Control`. Delegating means this launcher does not have to
  know which build it is talking to.

.PARAMETER Install
  INGenious install folder (the one containing `ingenious.bat`). Default: found — see
  Resolve-Install below.

.PARAMETER JavaHome
  Force a specific JDK. Still version-checked; a too-old one is refused, not used.

.PARAMETER Check
  Report the JDK and install that would be used, then exit. Launches nothing.
  Exit code 0 = this machine can start INGenious.

.PARAMETER IngeniousArgs
  Everything else is passed to `ingenious.bat` unchanged. No arguments => Studio (GUI).

.EXAMPLE
  # Tester: start Studio
  .\ingenious-launch.ps1

.EXAMPLE
  # Engineer: run a test set headlessly (legacy CLI arguments, 3.0 and 3.1)
  .\ingenious-launch.ps1 -project_location "Projects\HandoffProof" -release Release1 `
                         -testset HandoffSet -browser Chromium -run -quit -dont_launch_report

.EXAMPLE
  # Is this machine able to run INGenious at all?
  .\ingenious-launch.ps1 -Check

.NOTES
  Exit codes: 0 ok · 2 no INGenious install · 3 no usable JDK · otherwise INGenious' own.
#>
[CmdletBinding()]
param(
  [string]$Install,
  [string]$JavaHome,
  [switch]$Check,
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$IngeniousArgs
)

$ErrorActionPreference = 'Continue'
$MinMajor = 17

function Info($m) { Write-Host $m }

# ---------------------------------------------------------------- JDK discovery

# Ask the JDK what it is instead of trusting the folder name: "jdk-17" is a directory a
# human typed, `java -version` is the thing that will actually run the bytecode.
function Test-JavaHome([string]$dir) {
  if (-not $dir) { return $null }
  $dir = $dir.Trim().Trim('"')
  $exe = Join-Path $dir 'bin\java.exe'
  if (-not (Test-Path -LiteralPath $exe)) { return $null }

  $out = (& $exe -version 2>&1 | Out-String)
  if ($out -notmatch 'version\s+"([^"]+)"') { return $null }
  $ver = $Matches[1]

  # 1.8.0_431 -> 8 ; 17.0.12 -> 17 ; 21.0.10 -> 21
  if ($ver -match '^1\.(\d+)')  { $major = [int]$Matches[1] }
  elseif ($ver -match '^(\d+)') { $major = [int]$Matches[1] }
  else { return $null }

  $sortable = [version]'0.0'
  $numeric  = ($ver -replace '[^0-9.].*$', '').Trim('.')
  try { if ($numeric) { $sortable = [version]$numeric } } catch { }

  [pscustomobject]@{ Path = (Resolve-Path -LiteralPath $dir).Path; Version = $ver; Major = $major; Sortable = $sortable }
}

$SearchedRoots = @(
  "$env:ProgramFiles\Java"
  "$env:ProgramFiles\Microsoft"
  "$env:ProgramFiles\Eclipse Adoptium"
  "$env:ProgramFiles\Amazon Corretto"
  "$env:ProgramFiles\Zulu"
  "$env:ProgramFiles\Semeru"
  "${env:ProgramFiles(x86)}\Java"
  "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
  "$env:LOCALAPPDATA\Programs\Java"
  "$env:USERPROFILE\.jdks"
)

function Get-JavaCandidates {
  $seen = New-Object System.Collections.Generic.List[string]

  # Explicit intent first.
  foreach ($name in 'INGENIOUS_JAVA_HOME', 'JAVA_HOME') {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ($v) { $seen.Add($v) }
  }
  # Then every JDK-shaped folder in the usual places. Per-user roots are included because a
  # no-admin device is exactly where an unzipped JDK ends up under the user's own profile.
  foreach ($root in $SearchedRoots) {
    if (Test-Path -LiteralPath $root) {
      foreach ($d in (Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue)) { $seen.Add($d.FullName) }
    }
  }
  # Then whatever PATH offers - usually 1.8 here, but it costs nothing and on a healthy
  # machine it is the right answer.
  foreach ($c in (Get-Command java.exe -All -ErrorAction SilentlyContinue)) {
    $seen.Add((Split-Path (Split-Path $c.Source -Parent) -Parent))
  }
  $seen | Where-Object { $_ } | Select-Object -Unique
}

function Resolve-Java {
  if ($JavaHome) {
    $j = Test-JavaHome $JavaHome
    if (-not $j) { return @{ Chosen = $null; Rejected = @(); Forced = $JavaHome } }
    if ($j.Major -lt $MinMajor) { return @{ Chosen = $null; Rejected = @($j); Forced = $JavaHome } }
    return @{ Chosen = $j; Rejected = @() }
  }

  $found = @()
  foreach ($c in (Get-JavaCandidates)) {
    $j = Test-JavaHome $c
    if ($j) { $found += $j }
  }
  $found = $found | Sort-Object -Property @{ e = 'Path' } -Unique

  $usable = @($found | Where-Object { $_.Major -ge $MinMajor })
  if (-not $usable) { return @{ Chosen = $null; Rejected = @($found) } }

  # Lowest major that is still >= 17, newest patch within it. INGenious is built on 17, so
  # the closest thing to 17 is the least surprising choice; a JDK 25 that happens to be on
  # the machine is not what anyone tested.
  $chosen = $usable | Sort-Object @{ e = 'Major' }, @{ e = 'Sortable'; Descending = $true } | Select-Object -First 1
  @{ Chosen = $chosen; Rejected = @($found | Where-Object { $_.Major -lt $MinMajor }) }
}

function Show-NoJavaHelp($result) {
  Write-Host ''
  if ($result.Forced) {
    Write-Host "Das angegebene Java unter `"$($result.Forced)`" ist nicht brauchbar." -ForegroundColor Red
    Write-Host "The JDK given with -JavaHome cannot be used." -ForegroundColor Red
  } else {
    Write-Host "INGenious braucht Java $MinMajor oder neuer. Auf diesem Gerat wurde keins gefunden." -ForegroundColor Red
    Write-Host "INGenious needs Java $MinMajor or newer; none was found on this machine." -ForegroundColor Red
  }
  if ($result.Rejected -and $result.Rejected.Count) {
    Write-Host ''
    Write-Host 'Gefunden, aber zu alt / found but too old:'
    foreach ($r in ($result.Rejected | Sort-Object Major)) { Write-Host ("   Java {0,-12} {1}" -f $r.Version, $r.Path) }
  }
  Write-Host ''
  Write-Host 'Was Sie tun konnen / what to do:'
  Write-Host '  1. Wenn Sie wissen, wo ein JDK 17 liegt, geben Sie es mit an:'
  Write-Host '       ingenious-launch.cmd -JavaHome "C:\Program Files\Java\jdk-17"'
  Write-Host '  2. Sonst beim Testautomatisierungs-Team ein JDK 17 anfordern. Es sind keine'
  Write-Host '     Administratorrechte notig - ein entpacktes JDK im eigenen Benutzerordner reicht.'
  Write-Host ''
  Write-Host ('Gesucht wurde in / searched: INGENIOUS_JAVA_HOME, JAVA_HOME, PATH, ' + ($SearchedRoots -join ', '))
}

# ---------------------------------------------------------------- install discovery

function Resolve-Install {
  # 1. explicit  2. environment  3. the folder this script sits in (so the file can simply
  # be copied into an install)  4. the newest ingenious-playwright-* under ~\ingenious
  $tries = @()
  if ($Install) { $tries += $Install }
  if ($env:INGENIOUS_HOME) { $tries += $env:INGENIOUS_HOME }
  $tries += $PSScriptRoot
  foreach ($t in $tries) {
    if ($t -and (Test-Path -LiteralPath (Join-Path $t 'ingenious.bat'))) { return (Resolve-Path -LiteralPath $t).Path }
  }
  foreach ($root in @("$env:USERPROFILE\ingenious", "$env:USERPROFILE\INGenious", "$env:LOCALAPPDATA\ingenious")) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $hit = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
           Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'ingenious.bat') } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($hit) { return $hit.FullName }
  }
  $null
}

# ---------------------------------------------------------------- go

$java = Resolve-Java
if (-not $java.Chosen) { Show-NoJavaHelp $java; exit 3 }

$installDir = Resolve-Install
if (-not $installDir) {
  Write-Host ''
  Write-Host 'Kein INGenious gefunden (gesucht wird der Ordner mit der Datei ingenious.bat).' -ForegroundColor Red
  Write-Host 'No INGenious install found (looking for the folder that contains ingenious.bat).' -ForegroundColor Red
  Write-Host ''
  Write-Host 'Was Sie tun konnen / what to do:'
  Write-Host '  - Legen Sie diese Startdatei in den INGenious-Ordner (neben ingenious.bat), oder'
  Write-Host '  - geben Sie den Ordner an:  ingenious-launch.cmd -Install "C:\...\ingenious-playwright-3.1..."'
  exit 2
}

$version = Join-Path $installDir 'INSTALL-VERSION.txt'
Info ("Java      : {0}   ({1})" -f $java.Chosen.Version, $java.Chosen.Path)
Info ("INGenious : {0}" -f $installDir)
if (Test-Path -LiteralPath $version) {
  $c = (Get-Content -LiteralPath $version | Where-Object { $_ -match '^(describe|commit)\s*:' }) -join '  '
  if ($c) { Info ("            {0}" -f $c) }
}

if ($Check) {
  Info ''
  Info "OK - dieses Gerat kann INGenious starten. / This machine can start INGenious."
  exit 0
}

# Process-scoped only. Nothing here survives this script.
$env:JAVA_HOME = $java.Chosen.Path
$env:PATH      = (Join-Path $java.Chosen.Path 'bin') + ';' + $env:PATH

# The Studio panels shell out to this repository's Node tools — "Aufnahme abgeben" packages
# the project with tools/handoff-pack.mjs, and "Aus ADO aktualisieren" refetches the test-case
# cache. They find them through ING_QA_REPO, which until now only the team laptop's own
# start script set. On a Fachbereich device the buttons therefore reported, honestly but
# uselessly, that the hand-off was not set up.
#
# This script lives in the repository's tools/ folder, so the repository root is simply its
# parent — no configuration, and correct wherever the folder was copied to. An existing value
# is respected: someone who set it deliberately means it.
if (-not $env:ING_QA_REPO) {
  $repoRoot = Split-Path -Parent $PSScriptRoot
  # Only claim it when it actually looks like the repository, so a launcher copied out on its
  # own does not point the panels at an arbitrary folder — a wrong path fails later and further
  # away than no path at all.
  if ($repoRoot -and (Test-Path -LiteralPath (Join-Path $repoRoot 'tools\handoff-pack.mjs'))) {
    $env:ING_QA_REPO = $repoRoot
  }
}

$bat = Join-Path $installDir 'ingenious.bat'

# INGenious exits 0 even when the run dies: a misspelled -testset produces
# `NullPointerException ... ProjectRunner.getTestSet() is null` and errorlevel 0 (measured,
# 3.0.0-preview). So the two arguments most easily got wrong are checked here, where the
# answer can still be a sentence. Only a positively proven miss stops the run - argument
# shapes this does not recognise are passed through untouched.
function Assert-LegacyArgs([string[]]$a, [string]$installDir) {
  function ValOf($name) {
    $i = [array]::IndexOf($a, $name)
    if ($i -ge 0 -and $i + 1 -lt $a.Count) { return $a[$i + 1] }
    $null
  }
  $proj = ValOf '-project_location'
  if (-not $proj) { return }
  $projPath = if ([System.IO.Path]::IsPathRooted($proj)) { $proj } else { Join-Path $installDir $proj }
  if (-not (Test-Path -LiteralPath $projPath -PathType Container)) {
    Write-Host ''
    Write-Host "Das Projekt `"$proj`" gibt es nicht." -ForegroundColor Red
    Write-Host "No project at: $projPath" -ForegroundColor Red
    $siblings = Get-ChildItem -LiteralPath (Join-Path $installDir 'Projects') -Directory -ErrorAction SilentlyContinue
    if ($siblings) { Write-Host ('Vorhanden / available: ' + (($siblings.Name | ForEach-Object { "Projects\$_" }) -join ', ')) }
    exit 2
  }

  $rel = ValOf '-release'; $set = ValOf '-testset'
  if (-not $rel -or -not $set) { return }
  $lab = Join-Path $projPath 'TestLab'
  if (-not (Test-Path -LiteralPath $lab)) { return }
  $hit = Get-ChildItem -LiteralPath $lab -Recurse -File -ErrorAction SilentlyContinue |
         Where-Object { $_.Directory.Name -eq $rel -and $_.BaseName -eq $set }
  if ($hit) { return }
  Write-Host ''
  Write-Host "Das Testset `"$rel/$set`" gibt es in diesem Projekt nicht." -ForegroundColor Red
  Write-Host "No test set '$rel/$set' in this project." -ForegroundColor Red
  $all = Get-ChildItem -LiteralPath $lab -Recurse -File -ErrorAction SilentlyContinue |
         Where-Object { $_.Extension -in '.csv', '.yaml', '.yml' } |
         ForEach-Object { "$($_.Directory.Name)/$($_.BaseName)" } | Sort-Object -Unique
  if ($all) { Write-Host ('Vorhanden / available: ' + ($all -join ', ')) }
  else { Write-Host 'In TestLab liegt kein Testset. / TestLab holds no test set.' }
  Write-Host ''
  Write-Host 'Ohne diese Pruefung endet INGenious hier mit einem NullPointerException-Stacktrace'
  Write-Host 'und trotzdem mit Rueckgabewert 0. / Without this check INGenious throws an NPE and still exits 0.'
  exit 2
}

if ($IngeniousArgs -and $IngeniousArgs.Count -gt 0) {
  Assert-LegacyArgs $IngeniousArgs $installDir
  # Re-quote for display so the echoed line can be copied and re-run as-is.
  $shown = ($IngeniousArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
  Info ("Kommando  : ingenious.bat {0}" -f $shown)
  Info ''
  & $bat @IngeniousArgs
  $code = $LASTEXITCODE
  # INGenious does not report pass/fail through its exit code - read the run summary it
  # prints. This code only says whether the JVM started and got that far.
  exit $code
}

# GUI: ingenious.bat detaches via `start javaw`, so returning quickly says nothing about
# whether Studio came up. Diff the javaw processes around the call and say so plainly -
# "the window blinked and vanished" is the failure this launcher exists to remove.
#
# Start-Process, not `& $bat`: the detached javaw inherits this console's stdout handle, so
# calling the batch file directly makes PowerShell block until Studio is closed again -
# the launcher would appear to hang for as long as the tester works. Measured, not feared.
$before  = @(Get-Process javaw -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$outFile = Join-Path $env:TEMP ("ingenious-launch-{0}.log" -f $PID)
$errFile = "$outFile.err"
$inFile  = "$outFile.in"
New-Item -ItemType File -Path $inFile -Force -ErrorAction SilentlyContinue | Out-Null
Info 'Studio wird gestartet...'
# Both streams go to files. Every inherited handle has to be replaced, not just stdout:
# javaw keeps whatever it inherits open for its whole life, and any of ours left in its
# hands blocks a caller that reads this launcher's output through a pipe.
Start-Process -FilePath $env:ComSpec -ArgumentList '/c', "`"$bat`"" `
              -WorkingDirectory $installDir -WindowStyle Hidden `
              -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
              -RedirectStandardInput $inFile | Out-Null
Start-Sleep -Seconds 6
$new = @(Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Id })

if ($new.Count -gt 0) {
  Info ''
  Info ("Studio lauft (PID {0}). Das Fenster `"INGenious Playwright Studio`" erscheint" -f ($new.Id -join ', '))
  Info 'nach wenigen Sekunden. Dieses schwarze Fenster wird nicht mehr gebraucht.'
  exit 0
}

Write-Host ''
Write-Host 'Studio wurde gestartet, lauft aber nicht mehr - es hat sich sofort beendet.' -ForegroundColor Red
Write-Host 'Studio was started but is already gone - it exited immediately.' -ForegroundColor Red
Write-Host ''
Write-Host ("Das lag nicht an der Java-Version (verwendet wurde Java {0})." -f $java.Chosen.Version)
Write-Host 'Bitte melden und diese Zeilen mitschicken:'
Write-Host ("   Java      : {0}  {1}" -f $java.Chosen.Version, $java.Chosen.Path)
Write-Host ("   INGenious : {0}" -f $installDir)
$log = Join-Path $installDir 'log.txt'
if (Test-Path -LiteralPath $log) { Write-Host ("   Protokoll : {0}" -f $log) }
$tail = @($outFile, $errFile | Where-Object { Test-Path -LiteralPath $_ } |
          ForEach-Object { Get-Content -LiteralPath $_ -Tail 15 -ErrorAction SilentlyContinue })
if ($tail) { Write-Host ''; Write-Host 'Letzte Ausgabe / last output:'; $tail | ForEach-Object { Write-Host "   $_" } }
exit 1
