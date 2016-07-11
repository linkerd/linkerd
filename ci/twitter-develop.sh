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

function build(){
  local name=$1
  shift
  local sbt_cmd=$@
  if tracking_shas; then
    orig_sha=$(get_cached_sha $name)
    new_sha=$(git rev-list -1 HEAD)
    if [ "$orig_sha" != "$new_sha" ]; then
      run_sbt $sbt_cmd
    fi
    update_sha $name $new_sha
  else
    run_sbt $sbt_cmd
  fi
}

git clone --depth=1 --branch=develop https://github.com/twitter/util.git
git clone --depth=1 --branch=develop https://github.com/twitter/ostrich.git
git clone --depth=1 --branch=develop https://github.com/twitter/finagle.git
git clone --depth=1 --branch=develop https://github.com/twitter/scrooge.git
git clone --depth=1 --branch=develop https://github.com/twitter/twitter-server.git

cd $TMP_DIR/util
build util coverageOff publishLocal

cd $TMP_DIR/ostrich
build ostrich publishLocal

cd $TMP_DIR/scrooge
build scrooge-core scrooge-core/publishLocal

# building an sbt plugin requiressome special magic
ORIG_SCALA_VERSION=$SCALA_VERSION
SCALA_VERSION=2.10.6
build scrooge-sbt-plugin scrooge-generator/publishLocal scrooge-sbt-plugin/publishLocal
SCALA_VERSION=$ORIG_SCALA_VERSION

cd $TMP_DIR/finagle
build finagle \
      finagle-benchmark/publishLocal \
      finagle-core/publishLocal \
      finagle-http/publishLocal \
      finagle-http2/publishLocal \
      finagle-mux/publishLocal \
      finagle-netty4/publishLocal \
      finagle-netty4-http/publishLocal \
      finagle-ostrich4/publishLocal \
      finagle-stats/publishLocal \
      finagle-serversets/publishLocal \
      finagle-thrift/publishLocal \
      finagle-thriftmux/publishLocal \
      finagle-toggle/publishLocal \
      finagle-zipkin/publishLocal \
      finagle-zipkin-core/publishLocal

cd $TMP_DIR/twitter-server
build twitter-server publishLocal

# clean up
cd $BASE_DIR
rm -rf $TMP_DIR
