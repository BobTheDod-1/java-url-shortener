#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
find src -name '*.java' | sort > build/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d build/classes @build/sources.txt
jar --create --file url-shortener.jar --main-class shortener.Main -C build/classes .
