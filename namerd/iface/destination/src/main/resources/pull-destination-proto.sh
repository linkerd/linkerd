#!/usr/bin/env bash
cd ../protobuf
echo "Copying destination.proto from linkerd2 repo"
printf "//This file is added using pull-destination-proto.sh. DO NOT EDIT!\n$(curl https://raw.githubusercontent.com/linkerd/linkerd2-proxy-api/master/proto/destination.proto)" > destination.proto


echo "Copying net.proto from linkerd2 repo"
printf "//This file is added using pull-destination-proto.sh. DO NOT EDIT!\n$(curl https://raw.githubusercontent.com/linkerd/linkerd2-proxy-api/master/proto/net.proto)" > net.proto

