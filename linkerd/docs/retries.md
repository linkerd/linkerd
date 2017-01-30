# Retries

```yaml
routers:
- ...
  client:
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

linkerd can automatically retry requests on certain failures (for example,
connection errors) and can be configured via the retries block.

Key | Default Value | Description
--- | ------------- | -----------
budget | See [retry budget](#retry-budget-parameters) | Object that determins _how many_ failed requests are eligible to be retried.
backoff | See [retry backoff](#retry-backoff-parameters) | Object that determines which backoff algorithm should be used.


## Retry Budget Parameters

> For every 10 non-retry calls, allow 1 retry

```yaml
client:
  retries:
    budget:
      percentCanRetry: 0.1
```

> For every non-retry call, allow 2 retries

```yaml
client:
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

Uses a [decorrelated jitter](http://www.awsarchitectureblog.com/2015/03/backoff.html) backoff algorithm.

Key | Default Value | Description
--- | ------------- | -----------
minMs | _required_ | The minimum number of milliseconds to wait before each retry.
maxMs | _required_ | The maximum number of milliseconds to wait before each retry.


