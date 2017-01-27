grpc-finagle interop client/server
===============================

In order to test how well grpc-finagle interoperates with other grpc stacks, we
implement the [https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md](standard interop service).

## Current status

Some of the basic interop tests work, but many need improvements.

## Test implementation status

These test names are standard and defined in the above document.

- [x] `empty_unary` empty (zero bytes) request and response.
- [ ] `large_unary` single request and (large) response. (flow control deadlock) #929
- [ ] `client_streaming` request streaming with single response. (flow control deadlock) #982
- [x] `server_streaming` single request with response streaming.
- [ ] `ping_pong` full-duplex streaming. (flow control deadlock) #980
- [x] `empty_stream` full-duplex streaming with zero message. #981
- [x] `timeout_on_sleeping_server` fullduplex streaming on a sleeping server.
- [ ] `compute_engine_creds` large_unary with compute engine auth. (needs TLS) #895
- [ ] `service_account_creds` large_unary with service account auth. (needs TLS) #894
- [ ] `jwt_token_creds` large_unary with jwt token auth. (needs TLS) #893
- [ ] `per_rpc_creds` large_unary with per rpc token. (needs TLS) #891
- [ ] `oauth2_auth_token` large_unary with oauth2 token auth. (needs TLS)
- [x] `cancel_after_begin` cancellation after metadata has been sent but before payloads are sent.
- [x] `cancel_after_first_response` cancellation after receiving 1st message from the server.
- [x] `status_code_and_message` status code propagated back to client.
- [ ] `custom_metadata` server will echo custom metadata. (needs metadata support) #986
- [x] `unimplemented_method` client attempts to call unimplemented method.
- [x] `unimplemented_service` client attempts to call unimplemented service.

## How to test a grpc-finagle server against the official grpc-go interop client.
# build and run the interop server
```
$ ./sbt
> grpc-interop/run
```

# build and run the grpc-go interop client.
```
$ go get -u google.golang.org/grpc
$ go get -u cloud.google.com/go/compute/metadata
$ go get -u golang.org/x/oauth2
$ go get -u go/src/github.com/grpc/grpc-go/interop/client/client.go
$ go build -o go-grpc-interop-client  go/src/github.com/grpc/grpc-go/interop/client/client.go
$ ./go-grpc-interop-client -use_tls=false  -test_case=empty_unary -server_port=60001
```

# To find all the test cases you can run, use the --help flag.
`$ ./go-grpc-interop-client --help`

## build and run the grpc-java interop client.
First, you will need gradle installed. (`brew install gradle` on macOS)
```
$ git clone https://github.com/grpc/grpc-java.git
$ cd grpc-java
$ ./gradlew installDist -PskipCodegen=true
$  ./run-test-client.sh --use_tls=false --test_case=empty_unary --server_port=60001
```
