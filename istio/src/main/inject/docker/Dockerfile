# Based on https://github.com/istio/pilot/blob/master/docker/debug/Dockerfile
# Build linkerd inject image
#
#  docker build -t buoyantio/istio-init:v1 .
#  docker push buoyantio/istio-init:v1

FROM ubuntu:xenial
RUN apt-get update && \
      apt-get install -y \
      curl \
      iptables

ADD prepare_proxy.sh /usr/local/bin/prepare_proxy.sh
RUN chmod +x /usr/local/bin/prepare_proxy.sh

ENTRYPOINT ["/usr/local/bin/prepare_proxy.sh"]
