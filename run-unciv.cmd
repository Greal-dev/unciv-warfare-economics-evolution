@echo off
REM Launch Unciv with correct working directory (android/assets for maps access)
cd /d "%~dp0android\assets"
java -jar "../../desktop/build/libs/Unciv.jar" %*
