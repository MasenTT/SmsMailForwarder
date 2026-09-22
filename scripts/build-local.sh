#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tools_dir=$(dirname "$project_dir")/smsmail-tools
if [ -z "${JAVA_HOME:-}" ] && [ -x '/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java' ]; then
    export JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home'
fi
cd "$project_dir"
if [ -x "$tools_dir/gradle-9.3.1/bin/gradle" ]; then
    export GRADLE_USER_HOME="$tools_dir/gradle-home"
    exec "$tools_dir/gradle-9.3.1/bin/gradle" :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain "$@"
fi
exec ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain "$@"
