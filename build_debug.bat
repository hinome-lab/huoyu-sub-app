@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=E:\WorkBuddy\8\.build\gradle-home"
set "GRADLE=E:\WorkBuddy\8\.build\gradle\gradle-8.7\bin\gradle.bat"
cd /d "E:\WorkBuddy\8\huoyu-sub-app"
call "%GRADLE%" --no-daemon %*
echo BUILD_EXIT=%ERRORLEVEL%
