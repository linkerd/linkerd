# Load Balancer

*(for the [loadBalancer](config.md#load_balancer) key)*

Specifies a load balancer to use.  It must be an object containing keys:

  * *kind* -- One of the supported load balancers.
  * *enableProbation* -- Optional.  Controls whether endpoints are eagerly evicted from
    service discovery. (default: true)
    See Finagle's [LoadBalancerFactory.EnableProbation](https://github.com/twitter/finagle/blob/develop/finagle-core/src/main/scala/com/twitter/finagle/loadbalancer/LoadBalancerFactory.scala#L28)
  * Any options specific to the load balancer.

If unspecified, p2c is used.

### Example

```yaml
routers:
- ...
  client:
    loadBalancer:
      kind: ewma
      maxEffort: 10
      decayTimeMs: 15000
```

Current load balancers include:

[p2c]: https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-least-loaded
[ewma]: https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-peak-ewma
[aperture]: https://twitter.github.io/finagle/guide/Clients.html#aperture-least-loaded
[heap]: https://twitter.github.io/finagle/guide/Clients.html#heap-least-loaded

## [p2c][p2c]

p2c supports the following options (see [here][p2c] for option semantics and defaults):

* *maxEffort* -- Optional.

## [ewma][ewma]

ewma supports the following options (see [here][ewma] for option semantics and defaults):

* *maxEffort* -- Optional.
* *decayTimeMs* -- Optional.

## [aperture][aperture]

aperture supports the following options (see [here][aperture] for option semantics and defaults):

* *maxEffort* -- Optional.
* *smoothWindowMs* -- Optional.
* *lowLoad* -- Optional.
* *highLoad* -- Optional.
* *minAperture* -- Optional.

## [heap][heap]

heap does not support any options.
