#!/bin/bash

set -e

if [ "$(uname -s)" = "Darwin" ]; then
  os=osx
else
  os=linux
fi
arch=$(uname -m)

protocbin=.protoc
protocversion=3.0.0
protocurl="https://github.com/google/protobuf/releases/download/v${protocversion}/protoc-${protocversion}-${os}-${arch}.zip"

if [ ! -f "$protocbin" ]; then
  tmp=$(mktemp -d -t protoc.XXX)
  pushd "$tmp" > /dev/null
  curl -L --silent --fail -o "$protocbin.zip" "$protocurl"
  jar xf "$protocbin.zip"
  chmod +x bin/protoc
  popd > /dev/null
  mv "$tmp/bin/protoc" "$protocbin"
  rm -rf "$tmp"
fi

./$protocbin "$@"
