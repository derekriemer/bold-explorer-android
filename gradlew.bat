@rem Gradle wrapper script for Windows

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem  Gradle startup script for Windows
@rem ##########################################################################

@rem Set local scope for the variables
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Locate Java
if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
    if not exist "%JAVA_EXE%" (
        echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
        exit /b 1
    )
) else (
    for %%i in (java.exe) do set JAVA_EXE=%%~$PATH:i
    if not defined JAVA_EXE (
        echo ERROR: No java.exe found in PATH. Install JDK 17+ or set JAVA_HOME. 1>&2
        exit /b 1
    )
)

set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem Return the exit code
exit /b %ERRORLEVEL%
