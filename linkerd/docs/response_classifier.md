# HTTP Response Classifiers
> Example response classifier config

```yaml
routers:
- ...
  client:
  responseClassifier:
    kind: io.l5d.retryableRead5XX
```

Response classifiers determine which HTTP responses are considered to
be failures (for the purposes of success rate calculation) and which
of these responses may be [retried](#retries).

<aside class="notice">
These parameters are available to the classifier regardless of kind. Classifiers may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | `io.l5d.nonRetryable5XX` | Either [`io.l5d.nonRetryable5XX`](#non-retryable-5xx), [`io.l5d.retryableRead5XX`](#retryable-read-5xx), or [`io.l5d.retryableIdempotent5XX`](#retryable-idempotent-5xx).


## Non-Retryable 5XX

kind: `io.l5d.nonRetryable5XX`

All 5XX responses are considered to be failures and none of these
requests are considered to be retryable.

## Retryable Read 5XX

kind: `io.l5d.retryableRead5XX`

All 5XX responses are considered to be failures. However, `GET`,
`HEAD`, `OPTIONS`, and `TRACE` requests may be retried automatically.

<aside class="warning">
Requests with chunked bodies are NEVER considered to be retryable.
</aside>

## Retryable Idempotent 5XX

kind: `io.l5d.retryableIdempotent5XX`

Like _io.l5d.retryableRead5XX_, but `PUT` and `DELETE` requests may
also be retried.

<aside class="warning">
Requests with chunked bodies are NEVER considered to be retryable.
</aside>
