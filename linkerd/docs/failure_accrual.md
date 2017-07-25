# Failure Accrual

```yaml
routers:
- ...
  client:
    failureAccrual:
      kind: io.l5d.successRate
      successRate: 0.9
      requests: 1000
      backoff:
        kind: jittered
        minMs: 5000
        maxMs: 300000
```

linkerd uses failure accrual to track the number of requests that have failed to
a given node, and it will back off sending requests to any nodes whose failures
have exceeded a given threshold. Both the failure threshold and the backoff
behavior are configurable. By default, if linkerd observes 5 consecutive
failures from a node, it will mark the node as dead and only attempt to resend
it traffic in increasing intervals between 5 seconds and 5 minutes.

<aside class="notice">
  These parameters are available to failure accrual policies regardless of kind. Each policy may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.consecutiveFailures`](#consecutive-failures), [`io.l5d.successRate`](#success-rate), [`io.l5d.successRateWindowed`](#success-rate-windowed), or [`none`](#none).
backoff | jittered backoff from 5s to 300s | A [backoff policy](#retry-budget-parameters) that determines how long to wait before resending traffic.

<aside class="success">
  Learn more about failure accrual via <a target="_blank" href="https://twitter.github.io/finagle/guide/Clients.html#failure-accrual">Finagle's documentation</a>
</aside>

## Consecutive Failures

kind: `io.l5d.consecutiveFailures`

Observes the number of consecutive failures to each node, and backs off sending
requests to nodes that have exceeded the specified number of failures.

Key | Default Value | Description
--- | ------------- | -----------
failures | _required_ | Number of consecutive failures.

## Success Rate

kind: `io.l5d.successRate`

Computes an exponentially-weighted moving average success rate for each node,
and backs off sending requests to nodes that have fallen below the specified
success rate. The window size for computing success rate is constrained to a
fixed number of requests.

Key | Default Value | Description
--- | ------------- | -----------
successRate | _required_ | Target success rate.
requests | _required_ | Number of requests over which success rate is computed.

## Success Rate (windowed)

kind: `io.l5d.successRateWindowed`

Computes an exponentially-weighted moving average success rate for each node,
and backs off sending requests to nodes that have fallen below the specified
success rate. The window size for computing success rate is constrained to a
fixed time window.

Key | Default Value | Description
--- | ------------- | -----------
successRate | _required_ | Target success rate.
window | _required_ | Number of seconds over which success rate is computed.

## None

kind: `none`

Disables failure accrual altogether. This policy does not accept any additional
parameters.
