#!/usr/bin/env bash
cd ../protobuf || exit
LATEST_API_RELEASE=v0.1.1
BASE_PROTO_URL=https://raw.githubusercontent.com/linkerd/linkerd2-proxy-api/$LATEST_API_RELEASE/proto
PROTOFILES="destination.proto net.proto"


for proto_file in $PROTOFILES; do
  echo "Copying $proto_file from linkerd2 repo"
  printf "//This file is added using pull-destination-proto.sh. DO NOT EDIT!\\n%s" "$(curl $BASE_PROTO_URL/"$proto_file")" > "$proto_file"
done

