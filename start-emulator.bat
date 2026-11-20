@echo off
rem 启动模拟器（开启宿主机出声；无此参数部分版本会静音输出）
setlocal
set ANDROID_HOME=C:\tools\android-sdk
set PATH=%ANDROID_HOME%\emulator;%ANDROID_HOME%\platform-tools;%PATH%
start "" emulator -avd avplay35 -no-snapshot-load -gpu angle_indirect -allow-host-audio -audio win32
echo Wait boot, then: set-emulator-proxy.bat
exit /b 0