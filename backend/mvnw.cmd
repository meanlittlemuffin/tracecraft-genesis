@REM Maven Wrapper startup batch script
@echo off

set "JAVA_HOME=C:\Program Files\Java\jdk-15.0.1"
set "WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar"

"%JAVA_HOME%\bin\java.exe" -jar "%WRAPPER_JAR%" %*
