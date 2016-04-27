#!/bin/bash

# Publish docker images from circleci

# Usage:
# ./project/ci-publish-docker.sh

set -eu

if [ -z "$CIRCLECI" ]; then
  echo "For use in circleci only!">&2
  exit 1
fi

if [ -z "$DOCKER_CREDENTIALS" ]; then
  echo "DOCKER_CREDENTIALS not found!">&2
  exit 1
fi

mkdir -p ~/.docker && echo $DOCKER_CREDENTIALS > ~/.docker/config.json

./sbt linkerd/dockerBuildAndPush namerd/dockerBuildAndPush namerd/dcos:dockerBuildAndPush
