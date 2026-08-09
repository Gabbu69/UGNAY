@ECHO OFF
SETLOCAL
SET "MVNW_DIR=%~dp0"
SET "MVNW_PROJECT_DIR=%~dp0."
IF EXIST "%MVNW_DIR%.mvn\wrapper\maven-wrapper.jar" GOTO wrapper
SET "WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%WRAPPER_URL%' -OutFile '%MVNW_DIR%.mvn\wrapper\maven-wrapper.jar'"
IF ERRORLEVEL 1 EXIT /B 1
:wrapper
PUSHD "%MVNW_PROJECT_DIR%"
"%JAVA_HOME%\bin\java.exe" "-Dmaven.multiModuleProjectDirectory=%MVNW_PROJECT_DIR%" -classpath "%MVNW_DIR%.mvn\wrapper\maven-wrapper.jar" "org.apache.maven.wrapper.MavenWrapperMain" %*
SET "MVNW_EXIT_CODE=%ERRORLEVEL%"
POPD
IF NOT "%MVNW_EXIT_CODE%"=="0" EXIT /B %MVNW_EXIT_CODE%
ENDLOCAL
