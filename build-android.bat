@echo off
rem 一键构建脚本：默认打 Debug APK；也可以传任务名，例如
rem   build-android.bat :composeApp:testDebugUnitTest
setlocal
set JAVA_HOME=C:\tools\jdk-21.0.2
set ANDROID_HOME=C:\tools\android-sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%
cd /d "%~dp0"

if "%~1"=="" (
  call gradlew.bat :composeApp:assembleDebug --console=plain
) else (
  call gradlew.bat %* --console=plain
)
exit /b %ERRORLEVEL%
