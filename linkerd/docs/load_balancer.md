# Load Balancer

> Example load balancer configuration

```yaml
routers:
- ...
  client:
    loadBalancer:
      kind: ewma
      maxEffort: 10
      decayTimeMs: 15000
```

<aside class="notice">
These parameters are available to the loadbalancer regardless of kind. The loadbalancer may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | `p2c` | Either [`p2c`](#power-of-two-choices-least-loaded), [`ewma`](#power-of-two-choices-peak-ewma), [`aperture`](#aperture-least-loaded), [`heap`](#heap-least-loaded), or [`roundRobin`](#round-robin).
enableProbation | `false` | If `true`, removals from service discovery are treated as advisory and the removed endpoints will remain in the load balancer pool as long as they remain healthy. See Finagle's [LoadBalancerFactory.EnableProbation](https://github.com/twitter/finagle/blob/develop/finagle-core/src/main/scala/com/twitter/finagle/loadbalancer/LoadBalancerFactory.scala#L28).

[p2c]: https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-least-loaded
[ewma]: https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-peak-ewma
[aperture]: https://twitter.github.io/finagle/guide/Clients.html#aperture-least-loaded
[heap]: https://twitter.github.io/finagle/guide/Clients.html#heap-least-loaded
[roundRobin]: https://twitter.github.io/finagle/docs/com/twitter/finagle/loadbalancer/Balancers$.html#roundRobin

## Power of Two Choices: Least Loaded

kind: `p2c`

<aside class="success">
  Learn more about p2c and how to configure it via <a target="_blank" href="https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-least-loaded">Finagle's documentation</a>.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
maxEffort | `5` | The number of times a load balancer can retry if the previously picked node was marked unavailable.

## Power of Two Choices: Peak EWMA

kind: `ewma`

<aside class="success">
  Learn more about ewma and how to configure it via <a target="_blank" href="https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-peak-ewma">Finagle's documentation</a>.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
maxEffort | `5` | The number of times a load balancer can retry if the previously picked node was marked unavailable.
decayTimeMs | 10 seconds | The window of latency observations.

## Aperture: Least Loaded

kind: `aperture`

<aside class="success">
  Learn more about aperture and how to configure it via <a target="_blank" href="https://twitter.github.io/finagle/guide/Clients.html#aperture-least-loaded">Finagle's documentation</a>.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
maxEffort | `5` | The number of times a load balancer can retry if the previously picked node was marked unavailable.
smoothWin | 5 seconds |  The window of concurrent load observation.
lowLoad | `0.5` | The lower bound of the load band used to adjust an aperture.
highLoad | `2` | The upper bound of the load band used to adjust an aperture.
minAperture | `1` | The minimum size of the aperture.

## Heap: Least Loaded

kind: `heap`

<aside class="success">
  Learn more about heap and how to configure it via <a target="_blank" href="https://twitter.github.io/finagle/guide/Clients.html#heap-least-loaded">Finagle's documentation</a>
</aside>

## Round Robin

kind: `roundRobin`

<aside class="success">
  Learn more about roundRobin and how to configure it via <a target="_blank" href="https://twitter.github.io/finagle/docs/com/twitter/finagle/loadbalancer/Balancers$.html#roundRobin">Finagle's documentation</a>
</aside>

Key | Default Value | Description
--- | ------------- | -----------
maxEffort | `5` | The number of times a load balancer can retry if the previously picked node was marked unavailable.
