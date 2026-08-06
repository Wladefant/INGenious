<#
.SYNOPSIS
  Bring every part of the ING tester setup to the current state, in one command, and say what
  it is at afterwards.

.DESCRIPTION
  Three things drift apart independently on a tester machine, and on 2026-07-28 they did:

    1. this repository  - the Node tools the Studio panels start as child processes
    2. the built Studio - our INGenious fork, pinned by the `INGenious` submodule
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

  TWO MACHINES RUN THIS, AND THEY ARE NOT THE SAME MACHINE.

    * an ENGINEER's checkout - a real git working tree, with git and Maven on it. All three
      parts above can genuinely be brought forward. That is what this script was written for.
    * a TESTER's package installation - <Ziel>\repo beside <Ziel>\studio and <Ziel>\node,
      unpacked from ing-tester-paket.zip. Today repo\ is a plain copy, because
      tools\build-tester-package.ps1 copies files deliberately and the device has no git to
      use a clone with.

  WHICH OF THE TWO IS DECIDED BY THE PACKAGE SHAPE, AND THE PACKAGE SHAPE WINS. Not by the
  presence of a .git: the day repo\ ships as a real clone, a tester installation satisfies both
  tests at once, and a rule that lets .git decide sends exactly that device down the engineer
  path - to exit 2, because there is no git on it. A checkout is therefore a .git with NO
  package around it.

  Until 05.08.2026 this script assumed the first and was pointed at the second by the desktop
  shortcut "ING aktualisieren" that INSTALLIEREN.ps1 puts there. Measured on a test device:
  seven PowerShell stack traces with file names and line numbers, followed by "Der Zweig  folgt
  keinem Zweig auf dem Server" - an empty branch name in a sentence about a server - and exit
  code 1. On a device without git it exited 2 after one line, sending a tester to install
  something she is not allowed to install. Neither outcome told her what to do.

  So the layout is DETERMINED, not assumed, and each gets what it can actually have:
    * checkout      -> pull, submodules, Studio build, plugin build. Unchanged.
    * package       -> no git is touched at all. Everything that can be repaired locally is
                       repaired (Node is fetched and checksum-verified if it is missing), the
                       rest is measured and reported, and the run ends with the ONE sentence
                       that says what a human has to do: install a newer package.
  A git call that fails can no longer end the run in a stack trace either; every one of them
  goes through Git-Text, which returns an empty string and lets the sentence above it be the
  thing the tester reads.

  No admin rights, no .exe, nothing installed system-wide, nothing written outside the
  repository, the install folder, the package folder and Maven's own cache.

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
  Do not rebuild the Studio even when the submodule pin has moved. The move is still
  REPORTED, and the exit code is non-zero - a skipped step is never silent.

.PARAMETER Check
  Report the state of all three parts and change nothing. Exit 0 = everything is current.

.PARAMETER OhneNachladen
  Do not fetch a missing Node from nodejs.org. The absence is then reported as the dead end
  it is. Exists so that the no-network path can be PROVEN on a machine that has network -
  otherwise the sentence a tester would read is the one branch nobody ever runs.

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
  [switch]$Check,
  [switch]$OhneNachladen
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

# EVERY git call goes through here, and that is not tidiness. `(& git ...).Trim()` returns
# $null the moment git prints its error to stderr instead of a value, and .Trim() on $null is
# "You cannot call a method on a null-valued expression" plus a stack trace naming this file
# and a line number. Measured on a test device against a package installation: SEVEN of them
# in one run, wrapped around the one sentence that mattered. A tester reads the stack traces,
# not the sentence. Here git's own stderr is kept out of the return value and an empty string
# comes back, so the caller decides what to SAY instead of the interpreter deciding to shout.
function Git-Text {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
  if (-not $script:GitExe) { return '' }
  $out = & $script:GitExe @GitArgs 2>$null
  if ($LASTEXITCODE -ne 0 -or $null -eq $out) { return '' }
  return (($out -join "`n").Trim())
}
# Kurzform fuer "die ersten acht Stellen, wenn es ueberhaupt etwas gibt".
function Kurz($s) { if ($s -and $s.Length -ge 8) { return $s.Substring(0, 8) } return '(unbekannt)' }

# GIT WIRD GESUCHT WIE NODE, in derselben Reihenfolge und aus demselben Grund: auf einem
# gesperrten Geraet steht keines von beiden im PATH, also muss ein mitgeliefertes Werkzeug
# gefunden und ein ausdruecklich benanntes bevorzugt werden.
#   ING_GIT               ausdruecklich benannt - schlaegt alles
#   <Paket>\git\cmd\git.exe bzw. \bin\git.exe    mitgeliefert. MinGit legt git.exe in BEIDE
#                         Ordner; welcher davon existiert, haengt an der MinGit-Fassung, also
#                         werden beide gefragt statt einer geraten.
#   PATH                  der Entwicklungsrechner
#
# NACHGELADEN WIRD GIT NICHT, und das ist eine Entscheidung, keine Luecke. Node legt zu jeder
# Version eine SHASUMS256.txt neben das ZIP; genau daran haengt, dass hier ueberhaupt etwas aus
# dem Netz ausgepackt werden darf. Ohne dieselbe Pruefung wird nichts geholt. Fehlt git, wird
# das in einem Satz gesagt - eine Paket-Installation braucht ohnehin keines.
#
# Steht hier oben und nicht neben Finde-Node, weil die Fallunterscheidung "Arbeitsverzeichnis
# oder Paket" git bereits braucht und lange vor der Node-Suche laeuft.
function Finde-Git {
  $envGit = $env:ING_GIT
  if ($envGit -and (Test-Path -LiteralPath $envGit)) { return @{ p = $envGit; wo = 'ING_GIT' } }
  foreach ($rel in @('git\cmd\git.exe', 'git\bin\git.exe')) {
    $mit = Join-Path $PaketRoot $rel
    if (Test-Path -LiteralPath $mit) { return @{ p = $mit; wo = 'im Paket mitgeliefert' } }
  }
  $fromPath = Get-Command git -ErrorAction SilentlyContinue
  if ($fromPath) { return @{ p = $fromPath.Source; wo = 'PATH' } }
  return $null
}

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

# WELCHE ART VON INSTALLATION IST DAS? Zuerst gefragt, weil jede Antwort danach davon abhaengt
# - und weil die alte Reihenfolge (erst git verlangen, dann nachsehen) auf einem Geraet ohne
# git nach einer Zeile aufhoerte und der Testerin sagte, sie moege git besorgen. Kann sie nicht.
#
# Erkannt an der NACHBARSCHAFT, nicht an einem Namen: das Paket legt repo\, studio\ und node\
# nebeneinander und schreibt STUDIO-STARTEN.cmd, PAKET.txt und INSTALLIEREN.cmd daneben. Keiner
# dieser Namen entsteht je in einem Arbeitsverzeichnis, und jeder einzelne genuegt: einer
# Installation, der eine dieser Dateien fehlt, ist beschaedigt - sie ist deswegen kein Checkout.
$PaketRoot   = Split-Path -Parent $Repo
$paketMarker = @('studio\ingenious.bat', 'STUDIO-STARTEN.cmd', 'PAKET.txt', 'INSTALLIEREN.cmd')
$istPaket    = [bool](@($paketMarker |
                 Where-Object { Test-Path -LiteralPath (Join-Path $PaketRoot $_) }).Count)

# DIE PAKETFORM GEWINNT, AUCH WENN EIN .git DANEBENLIEGT. Bisher entschied allein das .git,
# und das ging genau so lange gut, wie repo\ im Paket eine reine Kopie ohne Historie war.
# Sobald repo\ als echter Klon ausgeliefert wird, erfuellt eine TESTER-Installation BEIDE
# Bedingungen. Entschiede dann weiter das .git, liefe ausgerechnet das Geraet, fuer das der
# Knopf "ING aktualisieren" ueberhaupt existiert, in den Entwicklerzweig und stiege dort mit
# Code 2 aus, weil auf einem gesperrten Geraet kein git zu finden ist. Ein Arbeitsverzeichnis
# ist deshalb: ein .git - als Ordner oder, bei Worktree und Untermodul, als Datei - UND keine
# Paketnachbarschaft ringsum.
$hatGitOrdner = (Test-Path -LiteralPath (Join-Path $Repo '.git'))
$istCheckout  = $hatGitOrdner -and -not $istPaket

$script:GitExe = ''
$gitWo = ''
$g = Finde-Git
if ($g) { $script:GitExe = $g.p; $gitWo = $g.wo }
$gitDa = [bool]$script:GitExe
if ($istCheckout) {
  Info 'Art   : Arbeitsverzeichnis (git). Werkzeuge, Studio und Plugin werden nachgezogen.'
  if (-not $gitDa) {
    Bad 'git ist auf diesem Rechner nicht zu finden. Ohne git gibt es kein Update.'
    Bad "Gesucht wurde: ING_GIT, dann git\cmd\git.exe und git\bin\git.exe unter $PaketRoot, dann der PATH."
    Bad 'git is not on this machine. Without it there is no update.'
    exit 2
  }
  Info ("git   : " + (Git-Text '--version') + "   ($gitWo - $script:GitExe)")
} elseif ($istPaket) {
  Info "Art   : Tester-Installation aus dem Paket ($PaketRoot)."
  if ($hatGitOrdner) {
    # Ein .git im Paket ist kein Grund, es zu benutzen. Der Stand einer Tester-Installation
    # kommt als neues Paket, nicht ueber einen Server, den dieses Geraet gar nicht erreicht.
    Info '        Sie bringt zwar ein .git mit, zieht sich aber bewusst nicht selbst nach:'
    Info '        ein neuerer Stand kommt als neues Paket. Geprueft und wo moeglich in Ordnung'
    Info '        gebracht wird sie trotzdem.'
  } else {
    Info '        Sie enthaelt kein git-Arbeitsverzeichnis und kann sich daher nicht selbst'
    Info '        neu holen. Geprueft und wo moeglich in Ordnung gebracht wird sie trotzdem.'
  }
} else {
  # Weder noch. Das ist keine Panne, ueber die geraten wird: es wird gesagt, was da ist.
  Bad  "Dieser Ordner ist weder ein git-Arbeitsverzeichnis noch eine Paket-Installation:"
  Bad  "  $Repo"
  Bad  'Erwartet war entweder ein .git darin oder ein studio\ daneben.'
  Bad  'Bitte diese Zeilen an die Testautomatisierung geben.'
  exit 2
}

# NODE. Gesucht wird in derselben Reihenfolge, in der das PRODUKT sucht - sonst meldet ein
# Update etwas anderes als das, was beim Knopfdruck passiert.
#   de.ing.qa.ado.NodeRuntime: System-Property oder Umgebungsvariable ING_NODE, sonst "node".
#   tools\paket\INSTALLIEREN.ps1: das Paket bringt node\node.exe mit, die Startdatei setzt
#   ING_NODE darauf. PATH ist nur noch die Rueckfallebene fuer aeltere Pakete.
#
# Die alte Fassung fragte NUR den PATH. Auf einem Testerinnen-Rechner steht dort nie ein node -
# genau deshalb liefern wir eines mit. Das Update meldete deshalb "node ist NICHT vorhanden"
# und markierte die Installation als veraltet, waehrend in Wirklichkeit alles lief. Eine
# Warnung, die bei jedem Update grundlos erscheint, wird nach dem zweiten Mal ignoriert - und
# dann auch die echte.

# DIE VERSION, DIE NACHGELADEN WIRD. Festgenagelt und nicht "die neueste": eine Pruefsumme
# gegen eine Version, die sich morgen aendert, ist keine Pruefsumme. Dies ist dieselbe, die
# tools\build-tester-package.ps1 ins Paket legt, und LIESMICH.txt verspricht 20 oder neuer.
$NodeVersion = 'v20.18.0'
# ZWEI QUELLEN, in dieser Reihenfolge, weil eine gesperrte Quelle kein Grund ist aufzugeben.
# Was hier NICHT steht und warum: github.com/nodejs/node/releases/download/... - am 05.08.2026
# auf einem Testgeraet gemessen, HTTP 404. Node haengt seine ZIPs nicht als Release-Anhang an
# GitHub; die URL ist falsch und nicht gesperrt. Eine Ausweichquelle, die es nicht gibt, ist
# schlimmer als keine: sie kostet einen Versuch und meldet den falschen Grund.
$NodeQuellen = @('https://nodejs.org/dist', 'https://nodejs.org/download/release')

# NODE NACHLADEN - als ZIP, ausgepackt, nie installiert.
#
# Der Fall, um den es geht: eine Testerin drueckt "aktualisieren" und liest "node ist nicht
# installiert". Auf einem gesperrten Firmengeraet ist das eine Sackgasse - sie DARF nichts
# installieren. Deshalb wird hier auch nichts installiert, sondern ausgepackt: kein Adminrecht,
# kein Eintrag in der Registrierung, kein PATH, nichts ausserhalb des Paketordners. Genau dort,
# wo ING_NODE und diese Datei ohnehin nachsehen.
#
# GEPRUEFT WIRD, WAS ANKOMMT. Eine Datei aus dem Netz auf einem solchen Geraet auszupacken, ohne
# zu wissen, ob sie die ist, die sie zu sein vorgibt, waere der eine Fehler, den dieses
# Werkzeug sich nicht leisten kann. Node legt zu jeder Version SHASUMS256.txt daneben; passt
# die Summe nicht, wird die Datei geloescht und die Quelle gilt als verbrannt, nicht als
# "wahrscheinlich schon in Ordnung".
function Hole-Node([string]$zielOrdner) {
  $name = "node-$NodeVersion-win-x64"
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }
  $tmp = Join-Path $env:TEMP ('ing-node-' + $PID)
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  try {
    foreach ($basis in $NodeQuellen) {
      $sumUrl = "$basis/$NodeVersion/SHASUMS256.txt"
      $zipUrl = "$basis/$NodeVersion/$name.zip"
      Info ("   Quelle: " + $zipUrl)
      $sums = ''
      try { $sums = (Invoke-WebRequest -Uri $sumUrl -UseBasicParsing -TimeoutSec 30 -EA Stop).Content }
      catch { Warn ("   Pruefsummen nicht erreichbar: " + $_.Exception.Message); continue }
      $erwartet = ''
      foreach ($z in ($sums -split "`n")) {
        if ($z -match ('^([0-9a-fA-F]{64})\s+\*?' + [regex]::Escape("$name.zip") + '\s*$')) { $erwartet = $Matches[1].ToLower() }
      }
      if (-not $erwartet) { Warn '   In SHASUMS256.txt steht keine Summe fuer diese Datei.'; continue }
      $zip = Join-Path $tmp "$name.zip"
      try { Invoke-WebRequest -Uri $zipUrl -OutFile $zip -UseBasicParsing -TimeoutSec 600 -EA Stop }
      catch { Warn ("   Herunterladen fehlgeschlagen: " + $_.Exception.Message); continue }
      $ist = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
      if ($ist -ne $erwartet) {
        Remove-Item -LiteralPath $zip -Force -EA SilentlyContinue
        Bad  '   PRUEFSUMME PASST NICHT - die Datei wurde geloescht und NICHT ausgepackt.'
        Info ("   erwartet " + $erwartet)
        Info ("   bekommen " + $ist)
        continue
      }
      Info ("   Pruefsumme stimmt ({0:N1} MB)." -f ((Get-Item -LiteralPath $zip).Length / 1MB))
      # Nur node.exe und LICENSE - genau die zwei Dateien, die auch ins Paket gehen. npm und
      # npx sind Batch-Dateien um node_modules\npm herum und werden von keinem Werkzeug hier
      # aufgerufen; jedes .mjs unter tools\ importiert ausschliesslich node:-Builtins.
      New-Item -ItemType Directory -Force -Path $zielOrdner | Out-Null
      Add-Type -AssemblyName System.IO.Compression.FileSystem -EA SilentlyContinue
      $z = [IO.Compression.ZipFile]::OpenRead($zip)
      try {
        foreach ($paar in @(@("$name/node.exe", 'node.exe'), @("$name/LICENSE", 'LICENSE'))) {
          $e = $z.GetEntry($paar[0])
          if ($e) { [IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $zielOrdner $paar[1]), $true) }
        }
      } finally { $z.Dispose() }
      $neu = Join-Path $zielOrdner 'node.exe'
      if (-not (Test-Path -LiteralPath $neu)) { Bad '   In der ZIP lag keine node.exe.'; continue }
      # Wirklich gestartet, und mit einem PATH ohne node - das ist das Geraet der Testerin.
      $alt = $env:PATH
      try { $env:PATH = [Environment]::GetFolderPath('System'); $v = (& $neu --version 2>&1 | Out-String).Trim() }
      finally { $env:PATH = $alt }
      if ($v -match '^v\d+') { Good ("   node $v liegt jetzt in $zielOrdner und startet ohne node im PATH."); return $neu }
      Bad ("   Das nachgeladene node startet nicht: " + $v)
    }
  } finally { Remove-Item -LiteralPath $tmp -Recurse -Force -EA SilentlyContinue }
  return ''
}

function Finde-Node {
  $envNode = $env:ING_NODE
  if ($envNode -and (Test-Path -LiteralPath $envNode)) { return @{ p = $envNode; wo = 'ING_NODE' } }
  # Das Paket legt node\ NEBEN repo\ und studio\. $Repo ist repo\, also eine Ebene hoeher.
  $bundled = Join-Path $PaketRoot 'node\node.exe'
  if (Test-Path -LiteralPath $bundled) { return @{ p = $bundled; wo = 'im Paket mitgeliefert' } }
  $fromPath = Get-Command node -ErrorAction SilentlyContinue
  if ($fromPath) { return @{ p = $fromPath.Source; wo = 'PATH' } }
  return $null
}

$nodeExe = ''
$nodeWo  = ''
$n = Finde-Node
if ($n) { $nodeExe = $n.p; $nodeWo = $n.wo }

if (-not $nodeExe -and $istPaket -and -not $Check -and -not $OhneNachladen) {
  # Der Fall, um den es geht: das Paket sollte ein node mitbringen und tut es nicht - geloescht,
  # oder aus einem Paket von vor 08.2026. Bis hierher endete das in "node fehlt" und einer
  # Testerin, die nichts tun kann. Jetzt wird es geholt, bevor ueberhaupt jemand gefragt wird.
  Warn 'node fehlt in dieser Installation. Es wird geholt (ZIP, ausgepackt - nichts installiert).'
  $geholt = Hole-Node (Join-Path $PaketRoot 'node')
  if ($geholt) { $nodeExe = $geholt; $nodeWo = 'nachgeladen von nodejs.org' }
}

if ($nodeExe) {
  # Dass die Datei da ist, ist nicht die Frage - ob sie startet, ist die Frage.
  $nodeVer = (& $nodeExe --version 2>&1 | Out-String).Trim()
  if ($LASTEXITCODE -eq 0 -and $nodeVer -match '^v\d+') {
    Info ("node  : $nodeVer   ($nodeWo - $nodeExe)")
  } else {
    Warn "node wurde gefunden ($nodeExe), startet aber nicht: $nodeVer"
    $script:Stale += 'node startet nicht'
  }
} elseif ($istPaket) {
  # DER EINE SATZ. In einer Paket-Installation ist ein fehlendes node kein "nicht ganz aktuell",
  # sondern der Grund, warum die Haelfte der Knoepfe nichts tut - und ein Mensch muss ran.
  #
  # ABER NUR, WENN ES AUCH VERSUCHT WURDE. Bei -Check und bei -OhneNachladen hat dieser Lauf
  # gar nicht erst nachgeladen, und "liess sich nicht holen" waere dann genau die Behauptung
  # ohne Messung, die dieses Werkzeug sonst ueberall abstellt. Gesagt wird, was gilt.
  $warum = if ($Check) { 'und wurde nicht geholt (-Check veraendert nichts).' }
           elseif ($OhneNachladen) { 'und durfte nicht geholt werden (-OhneNachladen).' }
           else { 'und liess sich nicht holen.' }
  Fail 'node' ('node fehlt ' + $warum + ' Bitte PROBLEM-MELDEN.cmd doppelklicken ' +
               'und die Datei an die Testautomatisierung schicken - ohne node bleiben die Knoepfe ' +
               'zu Azure DevOps, Objektkatalog und Aufnahme pruefen wirkungslos.')
} else {
  Warn 'node ist weder ueber ING_NODE noch im Paket noch im PATH zu finden.'
  Warn 'Die Werkzeuge werden aktualisiert, sind aber nicht startbar.'
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
# In einer Paket-Installation steht das Studio NEBEN dem repo\ und wird genannt, nicht gesucht.
# Sonst faende der Starter irgendein anderes INGenious auf dem Geraet - auf einem Testgeraet
# nicht wahrscheinlich, auf einem Entwicklungsrechner gemessen: er fand am 05.08.2026 die
# Entwicklungsinstallation und nicht die des Pakets. Ein Update, das das falsche Studio prueft
# und beschreibt, meldet einen Stand, den die Testerin gar nicht startet.
if (-not $Install -and $istPaket -and (Test-Path -LiteralPath (Join-Path $PaketRoot 'studio\ingenious.bat'))) {
  $Install = Join-Path $PaketRoot 'studio'
  Info "Studio : $Install (aus dem Paket)"
}
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

$branch = ''
$before = ''
$after  = ''

if (-not $istCheckout) {
  # PAKET-INSTALLATION. Hier gibt es nichts zu holen, und das ist kein Defekt, sondern die
  # Bauart: das Paket ist eine Kopie, damit auf dem Geraet der Testerin weder git noch ein
  # Netzzugang zum Server noetig ist. Gesagt wird, WORAUF sie steht - aus PAKET.txt, das der
  # Paketbau geschrieben hat - und nicht so getan, als sei etwas nachgezogen worden.
  $paketTxt = Join-Path $PaketRoot 'PAKET.txt'
  if (Test-Path -LiteralPath $paketTxt) {
    foreach ($z in (Get-Content -LiteralPath $paketTxt -EA SilentlyContinue)) {
      if ($z -match '^\s*(Stempel|gebaut am|Studio)\s*:\s*(.+?)\s*$') { Info ($Matches[1] + ' : ' + $Matches[2]) }
    }
  } else {
    Info 'Kein PAKET.txt neben dieser Installation - der Stand steht unten am Plugin.'
  }
  Info 'Diese Installation holt sich nichts selbst. Ein neuerer Stand kommt als neues Paket.'
} else {

$branch  = Git-Text '-C' $Repo 'rev-parse' '--abbrev-ref' 'HEAD'
$before  = Git-Text '-C' $Repo 'rev-parse' 'HEAD'
Info "Zweig  : $branch"
Info ("vorher : " + (Kurz $before))

$upstream = Git-Text '-C' $Repo 'rev-parse' '--abbrev-ref' '--symbolic-full-name' '@{u}'
if (-not $upstream) {
  # A branch with no upstream cannot be pulled, and `git pull` says so in a sentence that
  # reads like a configuration hint rather than like "your update did nothing".
  Fail 'repo' ("Der Zweig " + $branch + " folgt keinem Zweig auf dem Server. Es kann nichts geholt werden.")
} elseif ($Check) {
  & $script:GitExe -C $Repo fetch --quiet 2>$null | Out-Null
  $behind = Git-Text '-C' $Repo 'rev-list' '--count' ("HEAD.." + $upstream.Trim())
  if ($behind -and [int]$behind -gt 0) {
    Warn "$behind Aenderung(en) liegen bereit und sind NICHT geholt (Zweig $upstream)."
    $script:Stale += "$behind Aenderung(en) im Arbeitsverzeichnis nicht geholt"
  } else {
    Good "Aktuell gegenueber $upstream."
  }
} else {
  $dirty = (& $script:GitExe -C $Repo status --porcelain)
  if ($dirty) {
    # Refuse rather than stash: a stash on somebody else's machine is a thing they will not
    # find again, and this script must never be the reason work disappears.
    Fail 'repo' 'Es liegen eigene Aenderungen im Arbeitsverzeichnis. Bitte erst sichern; es wurde nichts geholt.'
    $dirty | Select-Object -First 10 | ForEach-Object { Info "   $_" }
  } else {
    Info "hole   : $upstream"
    & $script:GitExe -C $Repo pull --ff-only
    if ($LASTEXITCODE) {
      Fail 'repo' 'git pull --ff-only ist fehlgeschlagen (siehe oben). Es wurde nichts geaendert.'
    }
  }
}

$after = Git-Text '-C' $Repo 'rev-parse' 'HEAD'
if ($after -and $after -ne $before) {
  Good ("neu    : " + (Kurz $after))
  # WHICH tools moved, by name. This is the sentence that would have made the 2026-07-28
  # incident a non-event: five tool files appeared upstream and nobody's machine said so.
  $changed = @(& $script:GitExe -C $Repo diff --name-only $before $after -- tools)
  if ($changed.Count) {
    Info ("geaenderte Werkzeuge (" + $changed.Count + "):")
    $changed | ForEach-Object { Info "   $_" }
  } else {
    Info 'An den Werkzeugen selbst hat sich nichts geaendert.'
  }
  # DIESES SKRIPT HAT SICH GERADE SELBST ERNEUERT. PowerShell hat die alte Fassung schon
  # vollstaendig eingelesen; alles ab hier laeuft weiter aus der Fassung von VOR dem Holen.
  # Was die neue Fassung besser koennte, kann sie in diesem Lauf also nicht - und eine
  # Zusammenfassung, die danach "alles aktuell" sagt, behauptet mehr als gemessen wurde.
  #
  # KEIN NEUSTART MITTEN IM LAUF, bewusst. Ein Skript, das sich selbst wieder aufruft, muss
  # gegen genau eine Wiederholung abgesichert werden, waehrend es gleichzeitig ein zweites
  # Fenster oeffnet und eine halb ausgegebene Zusammenfassung hinterlaesst. Ein wahrer Satz
  # kostet einen zweiten Klick und keine dieser Fallen.
  if ($changed | Where-Object { $_ -match 'tools/ing-update\.(ps1|cmd)$' }) {
    Warn 'Dieses Aktualisieren-Skript wurde selbst erneuert. Der Rest dieses Laufs verwendet noch die alte Fassung.'
    $script:Stale += 'Bitte noch einmal auf "ING aktualisieren" klicken - die neue Fassung war in diesem Lauf noch nicht in Betrieb.'
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
    & $script:GitExe -C $Repo submodule update --init --recursive 2>&1 | ForEach-Object { Info $_ }
    if ($LASTEXITCODE) { Fail 'submodule' 'git submodule update ist fehlgeschlagen (siehe oben).' }
  }
}

}   # Ende des Zweigs "Arbeitsverzeichnis"

# ---------------------------------------------------------------- 2. the Studio

Step '2 von 3 - Studio (INGenious)'

$pinned = ''
if ($istCheckout) {
  $line = (Git-Text '-C' $Repo 'ls-tree' 'HEAD' 'INGenious') -replace "`n", ''
  if ($line -match '^\d+\s+commit\s+([0-9a-f]{40})\s') { $pinned = $Matches[1] }
}

$installedCommit = ''
$versionFile = Join-Path $installDir 'INSTALL-VERSION.txt'
if (Test-Path -LiteralPath $versionFile) {
  $m = Select-String -LiteralPath $versionFile -Pattern '^commit\s*:\s*([0-9a-f]{40})'
  if ($m) { $installedCommit = $m.Matches[0].Groups[1].Value }
}
Info "Installation : $installDir"
if ($installedCommit) { Info ("installiert  : " + (Kurz $installedCommit)) }
else                  { Info 'installiert  : unbekannt (keine INSTALL-VERSION.txt)' }
if ($istCheckout) {
  if ($pinned) { Info ("verlangt     : " + (Kurz $pinned)) }
  else         { Info 'verlangt     : unbekannt (kein INGenious-Untermodul)' }
}

$studioNeedsBuild = $false
if (-not $istCheckout) {
  # Ein Paket bringt sein Studio fertig mit. Es gegen einen Untermodul-Pin zu halten, den es
  # hier gar nicht gibt, hiesse eine Frage zu stellen, die auf diesem Geraet keine Antwort hat.
  # Geprueft wird deshalb das, was hier wahr sein KANN: laesst es sich ueberhaupt starten.
  if (Test-Path -LiteralPath (Join-Path $installDir 'ingenious.bat')) {
    Good 'Studio ist vorhanden und startbar (der Starter hat es oben bestaetigt).'
  } else {
    Fail 'studio' "In $installDir liegt keine ingenious.bat - das Paket ist unvollstaendig."
  }
} elseif (-not $pinned) {
  Warn 'Ohne Untermodul-Pin laesst sich nicht sagen, welcher Studio-Stand verlangt ist.'
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
  $script:Stale += ("Studio steht auf " + (Kurz $installedCommit) + ", verlangt ist " + (Kurz $pinned))
  Warn 'Es wurde nichts gebaut (-Check bzw. -SkipStudio).'
} elseif ($studioNeedsBuild) {
  if (-not $mvnPath) {
    Fail 'studio' 'Zum Bauen wird Maven gebraucht und es wurde keins gefunden. Bitte mit -Mvn angeben.'
  } else {
    # The submodule says both which repository and which commit. Read rather than repeated:
    # a URL typed twice is a URL that goes out of step, and a build against the wrong remote
    # is the single easiest way to produce an install that looks right and is a day old.
    $url = Git-Text '-C' $Repo 'config' '-f' '.gitmodules' 'submodule.INGenious.url'
    $setup = Join-Path $PSScriptRoot 'setup-ingenious-laptop.ps1'
    if (-not $url -or -not (Test-Path -LiteralPath $setup)) {
      Fail 'studio' 'Das Bau-Skript oder die Quell-Adresse des Untermoduls fehlt.'
    } else {
      Info ("baue Studio aus " + $url + " @ " + (Kurz $pinned) + " - das dauert einige Minuten.")
      $installRoot = Split-Path -Parent $installDir
      & powershell -NoProfile -ExecutionPolicy Bypass -File $setup `
          -RepoUrl $url -Ref $pinned `
          -SrcDir (Join-Path $Repo 'INGenious') `
          -InstallRoot $installRoot -JavaHome $javaHome -Mvn $mvnPath -Force
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
$stamp = ''
if ($istCheckout) {
  if (Git-Text '-C' $Repo 'status' '--porcelain') {
    $stamp = 'dirty'
  } else {
    $head = Git-Text '-C' $Repo 'rev-parse' 'HEAD'
    $onRemote = if ($head) { Git-Text '-C' $Repo 'branch' '-r' '--contains' $head } else { '' }
    if ($onRemote) { $stamp = $head } else { $stamp = 'unpublished' }
  }
  Info "Stempel   : $stamp"
}

if (-not $istCheckout) {
  # PAKET-INSTALLATION. Es gibt hier kein Maven und keinen Quellstand, gegen den sich ein
  # Stempel vergleichen liesse - repo\ingenious-plugin\src ist eine Kopie ohne Historie.
  # Also wird nicht gebaut und auch nicht so getan: gefragt wird nur das, was den Knopf im
  # Studio grau macht - liegt ueberhaupt ein JAR da, und sagt es, woher es kommt.
  if (-not (Test-Path -LiteralPath $installedJar)) {
    Fail 'plugin' ('In der Installation liegt kein Plugin - in Studio erscheinen die ING-Knoepfe ' +
                   'dann gar nicht. Bitte PROBLEM-MELDEN.cmd doppelklicken und die Datei an die ' +
                   'Testautomatisierung schicken; dieses Paket war unvollstaendig.')
  } elseif (-not $oldStamp) {
    Warn 'Das eingebaute Plugin sagt nicht, woher es stammt. Sein Stand ist nicht feststellbar.'
    $script:Stale += 'Plugin-JAR ohne Herkunftsangabe'
  } else {
    Good 'Ein Plugin ist eingebaut und nennt seine Herkunft.'
  }
} elseif ($Check) {
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
  # `clean` is load-bearing, not hygiene. Maven's up-to-date check does not know about
  # -Ding.qa.commit, so with a populated target/ it skips the repackage and the JAR keeps
  # the PREVIOUS commit's stamp. Reproduced: built at AAAA1111, then again at BBBB2222
  # without clean -- the manifest still read AAAA1111. RepoCheck would then measure a
  # tester's checkout against the wrong commit and tell them to update when they are current,
  # or stay silent when they are not. The whole point of the stamp is that it cannot lie.
  & $mvnPath -B -q clean package -DskipTests ("-Ding.qa.commit=" + $stamp) --file (Join-Path $pluginSrc 'pom.xml')
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

# Dieselben Dateien, die de.ing.qa.panel.RepoCheck ueberwacht, und aus demselben Grund: das
# ist die Liste, deren Fehlen einen Knopf grau machte, ohne es zu sagen. HIER ebenfalls
# geprueft, weil ein Update, das eines davon vermissen laesst, das im Moment des Updates
# sagen muss und nicht erst beim naechsten Oeffnen von Studio.
#
# ACHTUNG: diese Liste war eine handgepflegte Kopie und ist genau so auseinandergelaufen, wie
# es Kopien tun - sie nannte fuenf Werkzeuge, waehrend RepoCheck.REQUIRED bereits sieben
# fuehrte (der Objektkatalog und "Testlauf ansehen" fehlten). Eine Kontrolle, die eine
# veraltete Liste abhakt, meldet Vollstaendigkeit, die sie nie geprueft hat.
#
# Deshalb wird die Liste jetzt aus dem PRODUKT gelesen, wenn dessen Quelltext vorliegt, und
# die eingebaute Liste ist nur die Rueckfallebene fuer ein Paket ohne Quellen. Weicht die
# eingebaute von der gelesenen ab, wird das laut gesagt statt still verwendet.
$required = @(
  @{ p = 'tools\selector-uniqueness.mjs';      f = 'Aufnahme pruefen' },
  @{ p = 'tools\handoff-pack.mjs';             f = 'Aufnahme abgeben' },
  @{ p = 'tools\object-store.mjs';             f = 'Objektkatalog oeffnen' },
  @{ p = 'tools\render-dashboard.mjs';         f = 'Testlauf ansehen' },
  @{ p = 'tools\ado-testcases.mjs';            f = 'Testfaelle aus Azure DevOps holen' },
  @{ p = 'tools\parse-report.mjs';             f = 'Testergebnis nach Azure DevOps melden' },
  @{ p = 'tools\ado-upload.mjs';               f = 'Testergebnis nach Azure DevOps melden' }
)

# Aus dem Produkt lesen, wo es geht. RepoCheck.REQUIRED nennt teils Konstanten
# (SelectorCheck.TOOL_REL), teils Zeichenketten - beides wird aufgeloest.
$srcRoot = Join-Path $Repo 'ingenious-plugin\src\main\java\de\ing\qa'
$repoCheckSrc = Join-Path $srcRoot 'panel\RepoCheck.java'
if (Test-Path -LiteralPath $repoCheckSrc) {
  $txt = Get-Content -LiteralPath $repoCheckSrc -Raw
  $block = [regex]::Match($txt, 'REQUIRED\s*=\s*List\.of\((?<b>[\s\S]*?)\);')
  if ($block.Success) {
    $ausProdukt = @()
    foreach ($m in [regex]::Matches($block.Groups['b'].Value, '"(tools/[^"]+)"')) {
      $ausProdukt += $m.Groups[1].Value -replace '/', '\'
    }
    foreach ($m in [regex]::Matches($block.Groups['b'].Value, '(\w+)\.TOOL_REL')) {
      $klasse = Get-ChildItem -LiteralPath $srcRoot -Filter ($m.Groups[1].Value + '.java') -Recurse -EA SilentlyContinue |
                Select-Object -First 1
      if ($klasse) {
        $k = [regex]::Match((Get-Content -LiteralPath $klasse.FullName -Raw), 'TOOL_REL\s*=\s*"(tools/[^"]+)"')
        if ($k.Success) { $ausProdukt += $k.Groups[1].Value -replace '/', '\' }
      }
    }
    $ausProdukt = @($ausProdukt | Sort-Object -Unique)
    $eingebaut  = @($required | ForEach-Object { $_.p } | Sort-Object -Unique)
    $nurProdukt = @($ausProdukt | Where-Object { $eingebaut -notcontains $_ })
    $nurHier    = @($eingebaut  | Where-Object { $ausProdukt -notcontains $_ })
    if ($nurProdukt.Count -or $nurHier.Count) {
      Warn 'Diese Kontrolle und RepoCheck.REQUIRED nennen NICHT dieselben Werkzeuge:'
      $nurProdukt | ForEach-Object { Warn ("  nur im Produkt : " + $_) }
      $nurHier    | ForEach-Object { Warn ("  nur hier       : " + $_) }
      Warn 'Geprueft wird die Liste des Produkts - sie ist die massgebliche.'
      $required = @($ausProdukt | ForEach-Object { @{ p = $_; f = '(aus RepoCheck.REQUIRED)' } })
    } else {
      Info ("Werkzeugliste stimmt mit RepoCheck.REQUIRED ueberein (" + $ausProdukt.Count + ").")
    }
  }
}
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
$finalInstalledCommit = ''
$vf = Join-Path $installDir 'INSTALL-VERSION.txt'
if (Test-Path -LiteralPath $vf) {
  $m = Select-String -LiteralPath $vf -Pattern '^commit\s*:\s*([0-9a-f]{40})'
  if ($m) { $finalInstalledCommit = $m.Matches[0].Groups[1].Value }
}
if ($istCheckout) {
  $finalHead = Git-Text '-C' $Repo 'rev-parse' 'HEAD'
  $finalDesc = Git-Text '-C' $Repo 'log' '-1' '--date=short' '--format=%h%x20%ad%x20%s'
  Write-Host ("  Werkzeuge : " + (Kurz $finalHead) + "  (" + $branch + ")")
  if ($finalDesc) { Write-Host ("              " + $finalDesc) }
} else {
  Write-Host ("  Werkzeuge : aus dem Paket, " + $Repo)
}
# NODE NOCH EINMAL, FRISCH GEMESSEN. Nicht die Variable von oben: zwischen der Voraussetzungs-
# pruefung und hier liegt der ganze Lauf, und ein halb gegluecktes Update ist genau der Fall,
# in dem die Zusammenfassung mehr behauptet als am Ende noch stimmt. Was hier steht, ist der
# Zustand der Platte in diesem Moment - gemessen, indem die Datei gestartet wird.
$finalNode = Finde-Node
$nodeLaeuft = $false
if ($finalNode) {
  $fv = (& $finalNode.p --version 2>&1 | Out-String).Trim()
  $nodeLaeuft = ($LASTEXITCODE -eq 0 -and $fv -match '^v\d+')
  if ($nodeLaeuft) { Write-Host ("  Node      : " + $fv + "  (" + $finalNode.wo + ")") }
  else             { Write-Host ("  Node      : gefunden, startet aber nicht - " + $finalNode.p) }
} else {
  Write-Host '  Node      : FEHLT'
}
Write-Host ("  Studio    : " + $installDir)
if ($finalInstalledCommit) { Write-Host ("              " + (Kurz $finalInstalledCommit)) }
else                       { Write-Host '              Herkunft unbekannt' }
if ($finalStamp) { Write-Host ("  Plugin    : " + $finalStamp) }
else             { Write-Host '  Plugin    : kein JAR oder ohne Herkunftsangabe' }
Write-Host ("  Java      : " + $javaHome)
Write-Host ''

# Das Urteil wird aus der Messung oben gebildet und nicht aus dem, was versucht wurde. Ein
# Lauf, der das Arbeitsverzeichnis geholt hat und danach ohne startbares node dasteht, ist
# kein gelungener Lauf - die Testerin haette einen Werkzeugkasten, der sich fuer heil haelt.
if (-not $nodeLaeuft -and -not ($script:Failed -match '^node ')) {
  Fail 'node' 'Nach diesem Lauf ist auf diesem Rechner kein startbares node vorhanden.'
}

# BEIDE LISTEN, IMMER. Bis zum 05.08.2026 kehrte der Fehlerzweig sofort um und die
# Nicht-aktuell-Liste wurde nur gedruckt, wenn gar nichts fehlgeschlagen war. Auf einem
# Testgeraet gemessen: ein Lauf holte eine neuere Fassung dieses Skripts, der Hinweis "bitte
# noch einmal klicken" landete in dieser Liste - und verschwand, weil daneben der Plugin-Bau
# scheiterte.
# Der eine Satz, der die Lage aufloest, faellt damit genau dann weg, wenn es klemmt.
if ($script:Stale.Count) {
  Write-Host 'NICHT AKTUELL / NOT CURRENT:' -ForegroundColor Yellow
  $script:Stale | ForEach-Object { Write-Host "  * $_" -ForegroundColor Yellow }
  Write-Host ''
}
if ($script:Failed.Count) {
  Write-Host 'FEHLGESCHLAGEN / FAILED:' -ForegroundColor Red
  $script:Failed | ForEach-Object { Write-Host "  * $_" -ForegroundColor Red }
  Write-Host ''
  Write-Host 'Es ist NICHT alles aktuell. Bitte diese Ausgabe an die Testautomatisierung geben.'
  exit 1
}
if ($script:Stale.Count) {
  Write-Host 'Nichts ist fehlgeschlagen, aber es ist auch nicht alles aktuell.'
  exit 3
}
if (-not $istCheckout) {
  # DER EINE SATZ, der verlangt wurde: alles, was hier repariert werden konnte, ist
  # repariert - und was ein Mensch tun muss, steht in einem Satz statt in einem Rueckgabewert.
  Write-Host 'Diese Installation ist vollstaendig und startbar.' -ForegroundColor Green
  Write-Host 'Sie holt sich keinen neuen Stand selbst: dafuer bekommen Sie ein neues Paket von' -ForegroundColor Green
  Write-Host 'der Testautomatisierung und starten darin einmal INSTALLIEREN.cmd.' -ForegroundColor Green
  Write-Host 'Bitte Studio einmal neu starten.' -ForegroundColor Green
  exit 0
}
Write-Host 'Alles aktuell. Bitte Studio einmal neu starten.' -ForegroundColor Green
Write-Host 'Everything is current. Restart Studio once.' -ForegroundColor Green
exit 0
