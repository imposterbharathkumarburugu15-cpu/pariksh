@echo off
pushd "%~dp0android"
call ".\gradlew.bat" %*
set GRADLE_EXIT_CODE=%ERRORLEVEL%
popd
exit /b %GRADLE_EXIT_CODE%
