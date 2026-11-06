@echo off
rem Builds InvMgr's Windows installer from the desktop jar. Run it on Windows, from anywhere:
rem
rem     tools\installers\build-windows.cmd <invmgr-<version>.jar> <version> <out folder>
rem
rem It needs Eclipse Temurin 17.0.21+9 for Windows (TEMURIN_HOME, default C:\Tools\jdk-17.0.21+9)
rem and Inno Setup 7.1.1 (INNO_HOME, default C:\Tools\InnoSetup7). Out comes
rem InvMgr-<version>-windows-x64-setup.exe, and beside it the app folder it was made from,
rem app\InvMgr, which the license check reads.
rem
rem jpackage makes the app folder: the launcher InvMgr.exe, the jar, and a Java runtime cut from
rem Temurin by jlink. Then the build deletes the runtime's copy of Microsoft's Universal C Runtime
rem (ucrtbase.dll and the api-ms-win-*.dll files), which Windows 10 and later have built in and
rem always use instead, and adds the notice for the runtime's certificate list. Inno Setup
rem compiles the folder into one setup .exe with tools\installers\invmgr.iss.
setlocal enableextensions
set "JAR=%~f1"
set "VERSION=%~2"
set "OUT=%~f3"
if "%~3"=="" (echo Usage: build-windows.cmd ^<jar^> ^<version^> ^<out folder^> & exit /b 1)
if not defined TEMURIN_HOME set "TEMURIN_HOME=C:\Tools\jdk-17.0.21+9"
if not defined INNO_HOME set "INNO_HOME=C:\Tools\InnoSetup7"
set "HERE=%~dp0"
set "WORK=%OUT%\work"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%WORK%\input" || exit /b 1
copy "%JAR%" "%WORK%\input\" >nul || exit /b 1
rem Which parts of Java the app uses, read from the jar each time so a new import is never missed.
rem cmd drops the outer quotes of a command that starts with one, so it gets two pairs.
set "MODULES="
for /f "delims=" %%m in ('""%TEMURIN_HOME%\bin\jdeps" --ignore-missing-deps --multi-release 17 --print-module-deps "%JAR%""') do set "MODULES=%%m"
rem With no modules the jpackage line below would read --icon as the module list and fail on the
rem icon's path, which hides the real problem.
if not defined MODULES (echo jdeps named no Java modules for %JAR% & exit /b 1)
echo Java modules: %MODULES%
"%TEMURIN_HOME%\bin\jpackage" --type app-image --name InvMgr --app-version %VERSION% --vendor "Asterkazika LLC" --description "Top-down room inventory manager" --input "%WORK%\input" --main-jar "%~nx1" --main-class com.modcritic.invmgr.Launcher --add-modules %MODULES% --icon "%HERE%..\..\assets\icons\InvMgr.ico" --dest "%OUT%\app" || exit /b 1
del /q "%OUT%\app\InvMgr\runtime\bin\ucrtbase.dll" "%OUT%\app\InvMgr\runtime\bin\api-ms-win-*.dll" || exit /b 1
copy "%HERE%cacerts.md" "%OUT%\app\InvMgr\runtime\legal\java.base\cacerts.md" >nul || exit /b 1
attrib +r "%OUT%\app\InvMgr\runtime\legal\java.base\cacerts.md"
"%INNO_HOME%\ISCC.exe" /Q /DAppVersion=%VERSION% "/DAppImage=%OUT%\app\InvMgr" "/DOutputDir=%OUT%" "%HERE%invmgr.iss" || exit /b 1
rmdir /s /q "%WORK%"
dir /b "%OUT%"
