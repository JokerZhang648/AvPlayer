@echo off
rem 安装 Debug APK 到已连接的设备/模拟器（先 adb devices 确认能看到设备）
setlocal
set ANDROID_HOME=C:\tools\android-sdk
set PATH=%ANDROID_HOME%\platform-tools;%PATH%
cd /d "%~dp0"

adb devices
adb install -r "composeApp\build\outputs\apk\debug\composeApp-debug.apk"
exit /b %ERRORLEVEL%
