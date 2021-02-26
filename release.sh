#!/bin/bash

set -ex

clojure -Spom
clojure -M:jar

cd backend && clojure -Spom && clojure -M:jar && cd ..

COMMIT_COUNT="$(git rev-list --count HEAD)"
let "NEXT_PATCH=COMMIT_COUNT+1"
NEW_VERSION="$(date '+%Y.%m.%d')-alpha$NEXT_PATCH"

echo "Releasing $NEW_VERSION ..."

sed -i 's|^  <version>.*</version>$|  <version>'$NEW_VERSION'</version>|' pom.xml
sed -i 's|^  <version>.*</version>$|  <version>'$NEW_VERSION'</version>|' backend/pom.xml
sed -i 's|^    <tag>.*$|    <tag>v'$NEW_VERSION'</tag>|' pom.xml
sed -i 's|^    <tag>.*$|    <tag>v'$NEW_VERSION'</tag>|' backend/pom.xml

git add pom.xml
git add backend/pom.xml
git commit -m "Release v$NEW_VERSION"
git tag -a "v$NEW_VERSION" -m "Release v$NEW_VERSION"
git push --follow-tags

clojure -M:deploy

cd backend && clojure -M:deploy && cd ..

echo "Released $NEW_VERSION!"

rm *.pom.asc
rm backend/*.pom.asc