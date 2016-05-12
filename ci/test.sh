#!/bin/sh

set -eu

unitTests() {
  ./sbt coverage test coverageReport
}

# we can't compute coverage on e2e tests because it
# conflicts/overwrites unit coverage for some modules.
e2eTests() {
  ./sbt e2e:test integration:compile
}

case "${CIRCLE_NODE_TOTAL:-1}" in
1)
  unitTests
  e2eTests
  ci/linkerd-acceptance.sh
  ;;

*)
  worker="${CIRCLE_NODE_INDEX}/${CIRCLE_NODE_TOTAL}"
  case "$CIRCLE_NODE_INDEX" in
  0)
    echo "Running unit tests in worker ${worker}"
    unitTests
    ;;

  1)
    echo "Running e2e tests in worker ${worker}"
    e2eTests
    ci/linkerd-acceptance.sh
    ;;

  *)
    echo "Nothing to do in worker ${worker}" >&2
    ;;
  esac
esac
