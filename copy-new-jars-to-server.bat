@echo off
rem Copies the newest SMP plugin jars from this folder into server\plugins. STOP THE SERVER FIRST.
cd /d "%~dp0"
if not exist "server\plugins" (echo server\plugins not found & pause & exit /b 1)
tasklist /v 2>nul | find /i "paper" >nul && (echo A server window seems to be running. Stop it first. & pause & exit /b 1)
for %%J in (SMP-Core SMP-Economy SMP-Punish SMP-Catch SMP-Orders SMP-Crates SMP-Drill SMP-Protect SMP-Spawners SMP-Travel SMP-Kits SMP-Scoreboard SMP-Shop SMP-Auction SMP-Worth SMP-RTP SMP-Info SMP-Stats SMP-Daily SMP-Bounty SMP-Social SMP-Auth HavenBuilder) do (
  if exist "%%J-1.0.0.jar" (
    copy /y "%%J-1.0.0.jar" "server\plugins\%%J-1.0.0.jar" >nul && echo copied %%J-1.0.0.jar
  ) else (
    echo missing here: %%J-1.0.0.jar
  )
)
echo.
echo Done. Start the server again (a full restart, not /reload).
pause
