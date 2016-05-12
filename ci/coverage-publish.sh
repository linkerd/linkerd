#!/bin/sh

set -eu

if [ -z "${COVERALLS_REPO_TOKEN:-}" ]; then
  echo "COVERALLS_REPO_TOKEN must be set" >&2
  exit 1
fi

./sbt coverageAggregate
./sbt coveralls
