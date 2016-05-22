#!/bin/sh

set -eu

if [ "${TWITTER_DEVELOP:-}" = "1" ]; then
  mkdir -p ~/.gitshas
  export GIT_SHA_DIR=~/.gitshas
  ./ci/twitter-develop.sh
fi

./sbt update
