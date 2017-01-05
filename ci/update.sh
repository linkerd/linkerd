#!/bin/sh

set -eu

if [ "${TWITTER_DEVELOP:-}" = "1" ]; then
  export GIT_SHA_DIR=$HOME/.gitshas
  mkdir -p "$GIT_SHA_DIR"
  ./ci/twitter-develop.sh
fi

./sbt update
