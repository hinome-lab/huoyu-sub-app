@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=D:\WorkSpace\WorkBuddy\11\.build\gradle-home"
set "GRADLE=D:\WorkSpace\WorkBuddy\11\.build\gradle\gradle-8.7\bin\gradle.bat"
cd /d "D:\WorkSpace\WorkBuddy\11\huoyu-sub-app"
call "%GRADLE%" --no-daemon %*
echo BUILD_EXIT=%ERRORLEVEL%
