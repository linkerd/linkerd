#!/bin/sh

set -eu

if [ -n "${TWITTER_DEVELOP:-}" ]; then
  mkdir -p ~/.gitshas
  export GIT_SHA_DIR=~/.gitshas
  ./ci/twitter-develop.sh
fi

./sbt update
