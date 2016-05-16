# HTTP Response Classifiers

Response classifiers determine which HTTP responses are considered to
be failures (for the purposes of success rate calculation) and which
of these responses may be [retried](retries.md). A response classifier
config block must contain a `kind` parameter which indicates which classifier
plugin to use.  By default, the_nonRetryable5XX_ classifier is used.

## nonRetryable5XX

All 5XX responses are considered to be failures and none of these
requests are considered to be retryable.

## retryableRead5XX

All 5XX responses are considered to be failures. However, `GET`,
`HEAD`, `OPTIONS`, and `TRACE` requests may be retried automatically.

## retryableIdempotent5XX

Like _retryableRead5XX_, but `PUT` and `DELETE` requests may also be
retried.