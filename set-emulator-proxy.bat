@echo off
rem 模拟器走宿主机代理（Android 里 10.0.2.2 = 电脑的 localhost）
rem 用法: set-emulator-proxy.bat           默认 127.0.0.1:7897
rem       set-emulator-proxy.bat 7890     指定端口
rem       set-emulator-proxy.bat off      关闭全局代理
setlocal
set ANDROID_HOME=C:\tools\android-sdk
set PATH=%ANDROID_HOME%\platform-tools;%PATH%

if /i "%~1"=="off" (
  adb shell settings put global http_proxy :0
  echo Emulator HTTP proxy cleared.
  exit /b 0
)

set HOST_PORT=10.0.2.2
if "%~1"=="" (
  set HOST_PORT=10.0.2.2:7897
) else (
  set HOST_PORT=10.0.2.2:%~1
)

adb devices
adb shell settings put global http_proxy %HOST_PORT%
adb shell cmd media_session volume --stream 3 --set 15
adb shell settings put global audio_safe_volume_state 0
echo Emulator HTTP proxy set to %HOST_PORT% ^(ensure PC proxy listens, e.g. 127.0.0.1:%~1 or 7897^).
echo Media volume=max; boost Windows mixer for "Android Emulator" if still quiet.
exit /b %ERRORLEVEL%