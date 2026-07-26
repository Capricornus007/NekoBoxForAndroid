@echo off
setlocal enabledelayedexpansion

echo === Building NekoBox ossDebug ===

:: 1. Build
call .\gradlew.bat assembleOssDebug
if %ERRORLEVEL% neq 0 (
    echo Build failed!
    exit /b 1
)
echo Build succeeded!

:: 2. Check adb
where adb >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo adb not found. Make sure Android SDK platform-tools is in PATH.
    exit /b 1
)

:: 3. Get device
for /f "skip=1 tokens=1" %%a in ('adb devices 2^>nul') do (
    set "DEVICE=%%a"
    goto :device_found
)
:device_found

if "%DEVICE%"=="" (
    echo No Android device connected via adb. Please connect a device first.
    echo Run 'adb devices' to check.
    exit /b 1
)

echo Device: %DEVICE%

:: 4. Detect device ABI
for /f "tokens=*" %%a in ('adb shell getprop ro.product.cpu.abi 2^>nul') do set "ABI=%%a"
echo ABI: %ABI%

:: 5. Find matching APK
set "APK_DIR=app\build\outputs\apk\oss\debug"
set "APK_FILE="

if not "%ABI%"=="" (
    for %%f in ("%APK_DIR%\*-%ABI%-debug.apk") do (
        if exist "%%f" set "APK_FILE=%%f"
    )
)

:: Fallback: try arm64-v8a first, then armeabi-v7a
if "%APK_FILE%"=="" (
    for %%f in ("%APK_DIR%\*-arm64-v8a-debug.apk") do set "APK_FILE=%%f"
)
if "%APK_FILE%"=="" (
    for %%f in ("%APK_DIR%\*-armeabi-v7a-debug.apk") do set "APK_FILE=%%f"
)

if "%APK_FILE%"=="" (
    echo No matching APK found in %APK_DIR%
    dir "%APK_DIR%\*.apk" 2>nul
    exit /b 1
)

echo Installing: %APK_FILE%
adb install -r -d "%APK_FILE%"

if %ERRORLEVEL% equ 0 (
    echo === Done! NekoBox installed successfully ===
) else (
    echo Installation failed!
    exit /b 1
)

endlocal