#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh build.sh
mkdir -p build/test-classes
find test -name '*.java' | sort > build/tests.txt
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp build/classes -d build/test-classes @build/tests.txt
java -ea -cp build/classes:build/test-classes shortener.Tests
