#!/bin/bash
# Linkerd initialization script responsible for setting up port forwarding.
# Based on: https://github.com/istio/pilot/blob/master/docker/prepare_proxy.sh

set -o errexit
set -o nounset
set -o pipefail

usage() {
  echo "${0} -p PORT"
  echo ''
  echo '  -p: Specify the linkerd daemonset port to which redirect all TCP traffic'
  echo ''
}

while getopts ":p:" opt; do
  case ${opt} in
    p)
      LINKERD_PORT=${OPTARG}
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${LINKERD_PORT-}" ]]; then
  echo "Please set linkerd port -p"
  usage
  exit 1
fi

# Don't forward local traffic
iptables -t nat -A OUTPUT -d 127.0.0.1/32 -j RETURN                                       -m comment --comment "istio/bypass-explicit-loopback"

# Forward traffic to the daemonset linkerd router
iptables -t nat -A OUTPUT -p tcp -j DNAT --to ${L5D_SERVICE_HOST}:${LINKERD_PORT}         -m comment --comment "istio/dnat-to-daemonset-l5d"

# list iptables rules
iptables -t nat --list


