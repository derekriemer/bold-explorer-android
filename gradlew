#!/bin/sh
# Gradle wrapper script for Linux/macOS/WSL

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Resolve the script's directory
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then PRG="$link"
    else PRG=$(dirname "$PRG")/"$link"
    fi
done
SAVED=$(pwd)
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME=$(pwd -P)
cd "$SAVED" >/dev/null

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Locate Java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME ($JAVA_HOME) does not contain a valid Java installation." >&2
        exit 1
    fi
else
    JAVACMD=$(command -v java 2>/dev/null)
    if [ -z "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME not set and no 'java' command found in PATH." >&2
        echo "Install JDK 17+ or set JAVA_HOME." >&2
        exit 1
    fi
fi

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
