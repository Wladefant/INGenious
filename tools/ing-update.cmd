@echo off
rem ING aktualisieren - hier doppelklicken.
rem
rem Holt den neuen Stand der Werkzeuge, baut das Studio neu, wenn ein anderer Stand verlangt
rem ist, baut das Panel-Plugin und setzt es in die Installation - und sagt zum Schluss, worauf
rem alle drei Teile jetzt stehen.
rem
rem Nur nachsehen, ohne etwas zu veraendern:
rem   ing-update.cmd -Check
rem
rem -ExecutionPolicy Bypass gilt nur fuer diesen Aufruf und braucht keine Administratorrechte;
rem am Rechner wird dadurch nichts geaendert.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ing-update.ps1" %*
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (
  echo   [Fertig. Beliebige Taste = schliessen.]
) else (
  echo   [Es ist NICHT alles aktuell - bitte die Meldungen oben lesen. Beliebige Taste = schliessen.]
)
pause >nul
exit /b %RC%
