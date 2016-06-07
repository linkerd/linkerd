# Retries

*(for the [retries](config.md#retries) key)*

linkerd can automatically retry requests on certain failures (for example,
connection errors).  A retries config block is an object with the following
parameters:

* *budget* -- Optional. Determines _how many_ failed requests are
  eligible to be retried.
  * *minRetriesPerSec* -- Optional. The minimum rate of retries
    allowed in order to accommodate clients that have just started
    issuing requests as well as clients that do not issue many
    requests per window. Must be non-negative and if `0`, then no
    reserve is given. (Default: 10)
  * *percentCanRetry* -- Optional. The percentage of calls that can
    be retried. This is in addition to any retries allowed for via
    `minRetriesPerSec`.  Must be >= 0 and <= 1000. As an example, if
    `0.1` is used, then for every 10 non-retry calls , 1 retry will
    be allowed. If `2.0` is used then every non-retry call will
    allow 2 retries. (Default: 0.2)
  * *ttlSecs* -- Optional. The amount of time in seconds that
    successful calls are considered when calculating retry budgets
    (Default: 10)
* *backoff* -- Optional. Determines which backoff algorithm should
be used (see below).
  * *kind* -- The name of a backoff algorithm. Either _constant_ or
    _jittered_.

The _constant_ backoff policy takes a single configuration parameter:
* _ms_ -- The number of milliseconds to wait before each retry.

The _jittered_ backoff policy uses a
[decorrelated jitter](http://www.awsarchitectureblog.com/2015/03/backoff.html)
backoff algorithm and requires two configuration parameters:
* _minMs_ -- The minimum number of milliseconds to wait before each retry.
* _maxMs_ -- The maximum number of milliseconds to wait before each retry.

### Example

```yaml
routers:
- ...
  client:
    retries:
      budget:
        minRetiesPerSec: 5
        percentCanRetry: 0.5
        ttlSecs: 15
      backoff:
        kind: jittered
        minMs: 10
        maxMs: 10000
```