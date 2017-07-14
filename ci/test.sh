#!/bin/sh

set -eu

unitTests() {
  # Grpc tests need the protoc plugin to exist, but when it's built as part
  # of the test, it ends up with scoverage linked in and fails at
  # runtime.
  ./sbt test # 'set logLevel in Global := Level.Debug' coverage test coverageReport
}

# we can't compute coverage on e2e tests because it
# conflicts/overwrites unit coverage for some modules.
e2eTests() {
  ./sbt e2e:test integration:compile linkerd-protocol-http/integration:test
}

assembleBinaries() {
  ./sbt linkerd/assembly namerd/assembly
}

case "${CIRCLE_NODE_TOTAL:-1}" in
1)
  unitTests
  e2eTests
  assembleBinaries
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
    assembleBinaries
    ;;

  *)
    echo "Nothing to do in worker ${worker}" >&2
    ;;
  esac
esac
