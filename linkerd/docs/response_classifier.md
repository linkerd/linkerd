# HTTP Response Classifiers
> Example response classifier config

```yaml
routers:
- protocol: http
  service:
    responseClassifier:
      kind: io.l5d.http.retryableRead5XX
```

Response classifiers determine which HTTP responses are considered to
be failures (for the purposes of success rate calculation) and which
of these responses may be [retried](#retries).

<aside class="notice">
These parameters are available to the classifier regardless of kind. Classifiers may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | `io.l5d.http.nonRetryable5XX` | Either [`io.l5d.http.nonRetryable5XX`](#non-retryable-5xx), [`io.l5d.h2.nonRetryable5XX`](#non-retryable-5xx), [`io.l5d.http.retryableRead5XX`](#retryable-read-5xx), [`io.l5d.h2.retryableRead5XX`](#retryable-read-5xx), [`io.l5d.http.retryableIdempotent5XX`](#retryable-idempotent-5xx), or [`io.l5d.h2.retryableIdempotent5XX`](#retryable-idempotent-5xx).


## Non-Retryable 5XX

kind: `io.l5d.http.nonRetryable5XX`
kind: `io.l5d.h2.nonRetryable5XX`

All 5XX responses are considered to be failures and none of these
requests are considered to be retryable.

## Retryable Read 5XX

kind: `io.l5d.http.retryableRead5XX`
kind: `io.l5d.h2.retryableRead5XX`

All 5XX responses are considered to be failures. However, `GET`,
`HEAD`, `OPTIONS`, and `TRACE` requests may be retried automatically.

<aside class="warning">
Requests with chunked bodies are NEVER considered to be retryable.
</aside>

## Retryable Idempotent 5XX

kind: `io.l5d.http.retryableIdempotent5XX`
kind: `io.l5d.h2.retryableIdempotent5XX`

Like _io.l5d.http.retryableRead5XX_/_io.l5d.h2.retryableRead5XX_, but `PUT` and
`DELETE` requests may also be retried.

<aside class="warning">
Requests with chunked bodies are NEVER considered to be retryable.
</aside>

## All Successful

kind:  `io.l5d.http.allSuccessful`
kind:  `io.l5d.h2.allSuccessful`

All responses are considered to be successful, regardless of status code.

# gRPC Response Classifiers

For HTTP/2 routers that handle gRPC traffic, four additional response classifiers are available to categorize responses based on [gRPC status codes](https://github.com/grpc/grpc/blob/master/doc/statuscodes.md) in the stream's trailers frame. Status code 0 (`OK`) is always considered successful, while all other gRPC status codes are considered failures.

<aside class="notice">
Since H2 routing is experimental, all gRPC response classifiers are also marked as experimental and require `experimental: true` in the router configuration.
</aside>

## gRPC Default

kind:  `io.l5d.h2.grpc.default`

Status code 14 (`Unavailable`) is considered retryable, all other errors are non-retryable.

## gRPC Always Retryable

kind:  `io.l5d.h2.grpc.alwaysRetryable`

All gRPC error codes are considered retryable.

## gRPC Never Retryable

kind:  `io.l5d.h2.grpc.neverRetryable`

No gRPC error codes are considered retryable.

## gRPC User-Defined Retryable Status Codes
> Example config

```yaml
routers:
- protocol: h2
  experimental: true
  service:
    responseClassifier:
      kind: io.l5d.h2.grpc.retryableStatusCodes
      retryableStatusCodes:
      - 2
      - 5
      - 14
      - 100
```

This classifier accepts a user-defined list of error status codes to mark as retryable. Failures with status codes in the provided list will be classified as retryable, while all other failures will be non-retryable.
