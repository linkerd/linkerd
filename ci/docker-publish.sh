#!/bin/sh

set -eu

if [ -n "${DOCKER_CREDENTIALS:-}" ]; then
  mkdir -p ~/.docker
  echo "$DOCKER_CREDENTIALS" > ~/.docker/config.json
fi

set_tag=""
if [ "${NIGHTLY:-}" = "1" ] && [ ! "${TWITTER_DEVELOP:-}" = "1" ]; then
  set_tag='set dockerTag in Global := "nightly"'
fi

docker_target="dockerBuildAndPush"
if [ "${NO_PUSH:-}" = "1" ]; then
  docker_target="docker"
fi

./sbt "$set_tag" \
      "linkerd/bundle:${docker_target}" \
      "namerd/bundle:${docker_target}" \
      "namerd/dcos:${docker_target}"
