# Retries

```yaml
routers:
- ...
  service:
    retries:
      budget:
        minRetriesPerSec: 5
        percentCanRetry: 0.5
        ttlSecs: 15
      backoff:
        kind: jittered
        minMs: 10
        maxMs: 10000
```

Linkerd can automatically retry requests on certain failures and can be
configured via the retries block.  Retries fall into two categories: retries
and requeues.

Key | Default Value | Description
--- | ------------- | -----------
budget | See [retry budget](#retry-budget-parameters) | Object that determins _how many_ failed requests are eligible to be retried.
backoff | See [retry backoff](#retry-backoff-parameters) | Object that determines which backoff algorithm should be used.

## Retries

Retries are for application-level failures (such as 5XX responses in the case of
HTTP) as determined by the response classifier.  If the response classifier
determines that a request is a retryable failure, and the retry budget is not
empty, then the request will be retried.  Retries are configured by the
`retries` parameter on the `service` object.  On the `retries` object you may
specify the retry budget and retry backoff schedule.  Each service has its own
retry budget that is not shared with other services or clients.

## Requeues

Requeues are for connection-level failures that are guaranteed to be idempotent.
If a connection-level failure is encountered and there is requeue budget
available, then the request will be retried.  Requeue budgets are configured
by the `requeueBudget` parameter on the `client` object.  Requeues happen
immediately with no backoff.  Each client has its own requeue budget that is not
shared with other clients or services.

## Retry Budget Parameters

> For every 10 non-retry calls, allow 1 retry

```yaml
service:
  retries:
    budget:
      percentCanRetry: 0.1
```

> For every non-retry call, allow 2 retries

```yaml
service:
  retries:
    budget:
      percentCanRetry: 2.0
```

Key | Default Value | Description
--- | ------------- | -----------
minRetriesPerSec | `10` | The minimum rate of retries allowed in order to accommodate clients that have just started issuing requests, as well as clients that do not issue many requests per window. Must be non-negative. If `0`, no reserve is given.
percentCanRetry | `0.2` | The percentage of calls that can be retried. This is in addition to any retries allowed via `minRetriesPerSec`.  Must be >= `0` and <= `1000`.
ttlSecs | `10` | The amount of time in seconds that successful calls are considered when calculating retry budgets.

## Retry Backoff Parameters

<aside class="notice">
These parameters are available to the backoff regardless of kind. Backoffs may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`constant`](#constant-backoff) or [`jittered`](#jittered-backoff).

### Constant Backoff

kind: `constant`

Key | Default Value | Description
--- | ------------- | -----------
ms | `0` | The number of milliseconds to wait before each retry.

### Jittered Backoff

kind: `jittered`

Uses a [decorrelated jitter](https://www.awsarchitectureblog.com/2015/03/backoff.html) backoff algorithm.

Key | Default Value | Description
--- | ------------- | -----------
minMs | _required_ | The minimum number of milliseconds to wait before each retry.
maxMs | _required_ | The maximum number of milliseconds to wait before each retry.


