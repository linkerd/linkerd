# HTTP Response Classifiers

*(for the [responseClassifier](config.md#response_classifier) key)*

Response classifiers determine which HTTP responses are considered to
be failures (for the purposes of success rate calculation) and which
of these responses may be [retried](retries.md). A response classifier
config block must contain a `kind` parameter which indicates which classifier
plugin to use.  By default, the _io.l5d.nonRetryable5XX_ classifier is used.

### Example

```yaml
routers:
- ...
  client:
    responseClassifier:
      kind: io.l5d.retryableRead5XX
```

## Non-Retryable 5XX

`io.l5d.nonRetryable5XX`

All 5XX responses are considered to be failures and none of these
requests are considered to be retryable.

## Retryable Read 5XX

`io.l5d.retryableRead5XX`

All 5XX responses are considered to be failures. However, `GET`,
`HEAD`, `OPTIONS`, and `TRACE` requests may be retried automatically.

## Retryable Idempotent 5XX

`io.l5d.retryableIdempotent5XX`

Like _io.l5d.retryableRead5XX_, but `PUT` and `DELETE` requests may also be
retried.
