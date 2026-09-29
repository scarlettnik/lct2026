#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo '[1/5] versions'
grep -q '<java.version>11</java.version>' pom.xml
grep -q '<version>2.6.3</version>' pom.xml
grep -q '<springdoc.version>1.7.0</springdoc.version>' pom.xml

echo '[2/5] no known sample-specific routing IDs in production source'
if grep -REn 'R127|root127|C8\b|T14\b|T15\b|T16\b|T17\b|2259|2262' src/main/java; then
  echo 'dataset-specific marker found' >&2; exit 1
fi

echo '[3/5] Java-11-only syntax sanity'
if grep -REn 'case .*->|record[[:space:]]+[A-Za-z]|sealed[[:space:]]' src/main/java; then
  echo 'post-Java-11 syntax found' >&2; exit 1
fi

echo '[4/5] core classes compile with --release 11'
rm -rf .verify-classes && mkdir .verify-classes
javac --release 11 -d .verify-classes \
  src/main/java/ru/lct/teplokontur/domain/DnSpec.java \
  src/main/java/ru/lct/teplokontur/domain/RuleBook.java \
  src/main/java/ru/lct/teplokontur/domain/RestrictionRule.java \
  src/main/java/ru/lct/teplokontur/domain/RestrictionRules.java \
  src/main/java/ru/lct/teplokontur/domain/RunMode.java \
  src/main/java/ru/lct/teplokontur/domain/VariantPolicy.java
rm -rf .verify-classes

echo '[5/5] required files'
test -f Dockerfile && test -f docker-compose.yml && test -f src/main/resources/application.yml

echo 'STATIC VERIFICATION PASS'
echo 'Run `mvn test` or `docker-compose build` for the full dependency-aware build.'
