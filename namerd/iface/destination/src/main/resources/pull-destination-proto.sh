#!/usr/bin/env bash
cd ../protobuf || exit
DESTINATION_PROTO=$(curl https://raw.githubusercontent.com/linkerd/linkerd2-proxy-api/master/proto/destination.proto)
NET_PROTO=$(curl https://raw.githubusercontent.com/linkerd/linkerd2-proxy-api/master/proto/net.proto)

echo "Copying destination.proto from linkerd2 repo"
printf "//This file is added using pull-destination-proto.sh. DO NOT EDIT!\\n%s" "$DESTINATION_PROTO" > destination.proto


echo "Copying net.proto from linkerd2 repo"
printf "//This file is added using pull-destination-proto.sh. DO NOT EDIT!\\n%s" "$NET_PROTO" > net.proto

