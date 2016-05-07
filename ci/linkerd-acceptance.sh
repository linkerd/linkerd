#!/bin/sh

set -eu

# Start http server 9999
if [ -f "http.pid" ]; then
  kill "$(cat http.pid)" || rm -f http.pid
  sleep 1
fi
echo "Starting http server on 9999..."
python -m SimpleHTTPServer 9999 &
echo $! > http.pid

http_test() {
  # HTTP requests
  for i in $(seq 10); do
    status_code=$(curl -v -o /dev/null -H 'Host: web' localhost:4140 2>&1 | grep '^< HTTP' | awk '{print $3}')
    if [ "$status_code" != "200" ]; then
      echo "HTTP REQUEST FAILED"
      request_failed=true
    fi
  done
}

cleanup() {
  # Stop http server
  kill "$(cat http.pid)"
  rm -f http.pid
}

wait_for_running() {
  for i in $(seq $1); do
    pong=$(curl -s localhost:9990/admin/ping)
    if [ "$pong" = "pong" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

run_tests() {
  export LOG_LEVEL=DEBUG

  curl -s localhost:9990/admin/shutdown || true
  ./sbt linkerd-examples/acceptance-test:run &

  # Wait for linkerd to initialize
  if ! wait_for_running 200 ; then
    echo "could not start linkerd" >&2
    cleanup
    exit 1
  fi

  # Send "load"; TODO: send actual load
  request_failed=false

  http_test

  # Stop linkerd
  curl -s localhost:9990/admin/shutdown

  if [ $request_failed = true ]; then
    # Report failure, leave tmp dir intact
    echo "At least one request to linkerd failed." >&2
    echo " => Ensure that you have a local service running on port 9999" >&2
    echo " => Inspect logs at ./logs" >&2
    cleanup
    exit 1
  fi
}

run_tests
cleanup
echo "Success!"
