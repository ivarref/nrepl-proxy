#!/bin/bash

set -ex

clojure -A:jar

COMMIT_COUNT="$(git rev-list --count HEAD)"
let "NEXT_PATCH=COMMIT_COUNT+1"

sed -i 's|<version>\(.*\..*\.\).*</version>\(<!-- VERSION -->\)|<version>\1'$NEXT_PATCH'</version>\2|' pom.xml

RAW_VERSION="$(grep "VERSION -->" pom.xml | sed 's|^.*<version>\(.*\)</version>.*$|\1|')"

NEW_VERSION="v$RAW_VERSION"
echo "Releasing >$NEW_VERSION< ..."

git add pom.xml
git commit -m "Release $NEW_VERSION"
git tag -a $NEW_VERSION -m "Release $NEW_VERSION"
git push --follow-tags

clojure -A:deploy