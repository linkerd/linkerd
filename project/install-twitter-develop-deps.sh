#!/bin/bash

# Fetch twitter dependencies from the develop branches and
# publishLocal -SNAPSHOT dependencies.

set -xeu

# Based on Finagle's dependency-fetching script.

# Optionally accept the scala version as an argument
SCALA_VERSION=${1:-2.11.7}

# Allow git shas to be cached so that rebuilds aer only done as needed.
function tracking_shas(){
  [ -z "${IGNORE_GIT_SHA_DIR:-}" ] && [ -n "$GIT_SHA_DIR" ] && [ -d $GIT_SHA_DIR ]
}

function get_cached_sha(){
  local name=$1
  local path=${GIT_SHA_DIR}/${name}.sha
  if tracking_shas && [ -f $path ]; then
    cat $path
  else
    echo
  fi
}

function update_sha(){
  local name=$1
  local sha=$2
  local path=${GIT_SHA_DIR}/${name}.sha
  if tracking_shas; then
    echo $sha > $path
  fi
}

BASE_DIR=$(pwd -P)
TMP_DIR=$(mktemp -d -t linkerd.XXXXXX.tmp)
cd $TMP_DIR

function run_sbt() {
  if [ ! -f sbt-launch.jar ] && [ -f $TMP_DIR/sbt-launch.jar ]; then
    cp $TMP_DIR/sbt-launch.jar sbt-launch.jar
  fi
  ./sbt ++$SCALA_VERSION $@
  if [ -f sbt-launch.jar ] && [ ! -f $TMP_DIR/sbt-launch.jar ]; then
    cp sbt-launch.jar $TMP_DIR/
  fi
}

git clone --depth=1 --branch=develop https://github.com/twitter/util.git
git clone --depth=1 --branch=develop https://github.com/twitter/ostrich.git
git clone --depth=1 --branch=develop https://github.com/twitter/finagle.git
git clone --depth=1 --branch=develop https://github.com/twitter/scrooge.git
git clone --depth=1 --branch=develop https://github.com/twitter/twitter-server.git

cd $TMP_DIR/util
if tracking_shas; then
  orig_sha=$(get_cached_sha util)
  new_sha=$(git rev-list -1 --abbrev-commit HEAD)
  if [ "$orig_sha" != "$new_sha" ]; then
    run_sbt publishLocal
  fi
  update_sha util $new_sha
else
  run_sbt publishLocal
fi

cd $TMP_DIR/ostrich
if tracking_shas; then
  orig_sha=$(get_cached_sha ostrich)
  new_sha=$(git rev-list -1 --abbrev-commit HEAD)
  if [ "$orig_sha" != "$new_sha" ]; then
    run_sbt publishLocal
  fi
  update_sha ostrich $new_sha
else
  run_sbt publishLocal
fi

cd $TMP_DIR/scrooge
if tracking_shas; then
  orig_sha=$(get_cached_sha scrooge-core)
  new_sha=$(git rev-list -1 --abbrev-commit HEAD)
  if [ "$orig_sha" != "$new_sha" ]; then
    run_sbt scrooge-core/publishLocal
  fi
  update_sha scrooge-core $new_sha
else
  run_sbt scrooge-core/publishLocal
fi

cd $TMP_DIR/finagle
if tracking_shas; then
  orig_sha=$(get_cached_sha finagle)
  new_sha=$(git rev-list -1 --abbrev-commit HEAD)
  if [ "$orig_sha" != "$new_sha" ]; then
    run_sbt finagle-core/publishLocal \
        finagle-http/publishLocal \
        finagle-http2/publishLocal \
        finagle-mux/publishLocal \
        finagle-ostrich4/publishLocal \
        finagle-thrift/publishLocal \
        finagle-thriftmux/publishLocal \
        finagle-stats/publishLocal \
        finagle-serversets/publishLocal \
        finagle-zipkin/publishLocal \
        finagle-benchmark/publishLocal
  fi
  update_sha finagle $new_sha
else
  run_sbt finagle-core/publishLocal \
      finagle-http/publishLocal \
      finagle-http2/publishLocal \
      finagle-mux/publishLocal \
      finagle-ostrich4/publishLocal \
      finagle-thrift/publishLocal \
      finagle-thriftmux/publishLocal \
      finagle-stats/publishLocal \
      finagle-serversets/publishLocal \
      finagle-zipkin/publishLocal \
      finagle-benchmark/publishLocal
fi

cd $TMP_DIR/twitter-server
if tracking_shas; then
  orig_sha=$(get_cached_sha twitter-server)
  new_sha=$(git rev-list -1 --abbrev-commit HEAD)
  if [ "$orig_sha" != "$new_sha" ]; then
    run_sbt publishLocal
  fi
  update_sha twitter-server $new_sha
else
  run_sbt publishLocal
fi

# clean up
cd $BASE_DIR
rm -rf $TMP_DIR
