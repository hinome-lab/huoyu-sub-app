@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=E:\WorkBuddy\11\.build\gradle-home"
set "GRADLE=E:\WorkBuddy\11\.build\gradle\gradle-8.7\bin\gradle.bat"
cd /d "E:\WorkBuddy\11\huoyu-sub-app"
call "%GRADLE%" --no-daemon %*
echo BUILD_EXIT=%ERRORLEVEL%
