@echo off
rem INGenious launcher - double-click this file to start Studio on a JDK 17+.
rem Everything you type after the file name is passed straight through, e.g.
rem   ingenious-launch.cmd -Check
rem   ingenious-launch.cmd -project_location "Projects\HandoffProof" -release Release1 -testset HandoffSet -run -quit
rem
rem -ExecutionPolicy Bypass is process-scoped and needs no admin rights; it changes
rem nothing on the machine.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ingenious-launch.ps1" %*
set RC=%ERRORLEVEL%
if not "%RC%"=="0" (
  echo.
  echo   [Fenster bleibt offen, damit Sie die Meldung lesen koennen. Beliebige Taste = schliessen.]
  pause >nul
)
exit /b %RC%
