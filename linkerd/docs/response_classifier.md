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