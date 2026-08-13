@echo off
setlocal enabledelayedexpansion

set DIR=%~dp0
set OUT_DIR=%DIR%build
set CLASSES_DIR=%OUT_DIR%\classes
set JAR_PATH=%OUT_DIR%\vspManagerTool.jar

if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%CLASSES_DIR%"

echo Compiling sources...
dir /s /b "%DIR%src\*.java" > "%OUT_DIR%\sources.txt"
javac -d "%CLASSES_DIR%" @"%OUT_DIR%\sources.txt"
if errorlevel 1 exit /b 1

echo Packaging %JAR_PATH%...
jar --create --file "%JAR_PATH%" --main-class com.kpit.vspmanager.Main -C "%CLASSES_DIR%" .
if errorlevel 1 exit /b 1

echo Build complete: %JAR_PATH%
echo Run with: java -jar "%JAR_PATH%"
