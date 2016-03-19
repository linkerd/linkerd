#!/usr/bin/env bash

set -e

# Start http server 9999
if [ -f "http.pid" ]; then
  kill `cat http.pid`
  sleep 1
fi
echo "Starting http server on 9999..."
python -m SimpleHTTPServer 9999 &
echo $! > http.pid

function http_test {
  # HTTP requests
  for i in `seq 1 10`; do
    status_code=$(curl -v -o /dev/null -H 'Host: web' localhost:4140 2>&1 | grep '^< HTTP' | awk '{print $3}')
    if [ -z $status_code ] || [ $status_code != "200" ]; then
      echo "HTTP REQUEST FAILED"
      request_failed=true
    fi
  done
}

function cleanup {
  # Stop http server
  kill `cat http.pid` && rm http.pid
}

function run_tests {
  export LOG_LEVEL=DEBUG

  if [ -n "${TWITTER_DEVELOP}" ]; then
    ./sbt 'set developTwitterDeps in Global := true' examples/acceptance-test:run &
  else
    ./sbt examples/acceptance-test:run &
  fi

  # Wait for linkerd to initialize
  sleep 60

  # Send "load"; TODO: send actual load
  request_failed=false

  http_test

  # Stop linkerd
  curl localhost:9990/admin/shutdown

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
# Report success, cleanup tmp dir
rm -rf $tmpdir
echo "Success!"
