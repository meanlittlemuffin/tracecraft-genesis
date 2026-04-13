@REM Maven Wrapper startup batch script
@echo off

set "BASEDIR=%~dp0"
set "WRAPPER_JAR=%BASEDIR%.mvn\wrapper\maven-wrapper.jar"

java "-Dmaven.multiModuleProjectDirectory=%BASEDIR%." -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
