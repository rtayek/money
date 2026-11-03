#!/bin/sh
set -eu

case ${1-} in
    -h|--help)
        printf '%s\n' \
            'Usage: sh scripts/run-spending-plot.sh [--test-only] [SpendingPlot arguments]' \
            'Requires java and javac from JDK 25 or later on PATH.' \
            'Compiles the Java sources and tests, runs the tests, then opens SpendingPlot.' \
            'Use --test-only to skip the GUI.' \
            'CSV paths and generated reports are relative to the Money project directory.'
        exit 0
        ;;
esac

testOnly=false
if [ "${1-}" = '--test-only' ]; then
    testOnly=true
    shift
fi

scriptDir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
projectDir=$scriptDir
if [ ! -f "$projectDir/src/money/SpendingPlot.java" ]; then
    projectDir=$(CDPATH= cd -P "$scriptDir/.." && pwd)
fi
if [ ! -f "$projectDir/src/money/SpendingPlot.java" ]; then
    printf '%s\n' 'Place this script in the Money project root or its scripts directory.' >&2
    exit 1
fi
cd "$projectDir"

for tool in javac java; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Cannot find %s. Add JDK 25 or later to PATH.\n' "$tool" >&2
        exit 1
    fi
done

classesDir=build/spending-plot
mkdir -p "$classesDir"
printf '%s\n' 'Compiling Java sources and tests (release 25)...'
javac --release 25 -encoding UTF-8 -d "$classesDir" \
    src/module-info.java src/money/*.java tst/money/*Test.java

for testSource in tst/money/*Test.java; do
    testClass=${testSource##*/}
    testClass=${testClass%.java}
    printf 'Running %s...\n' "$testClass"
    java -Djava.awt.headless=true --module-path "$classesDir" \
        --module "money/money.$testClass"
done

printf '%s\n' 'All Java tests passed.'
if [ "$testOnly" = true ]; then
    exit 0
fi

printf '%s\n' 'Starting SpendingPlot...'
exec java --module-path "$classesDir" --module money/money.SpendingPlot "$@"
