## x.x.x

* Turn off HTTP decompression so that linkerd doesn't decompress and then
  recompress bodies.

## 0.7.0

* New default JVM settings scale up with traffic levels.
  * `JVM_HEAP` is now deprecated, you can now separately set `JVM_HEAP_MIN` and
    `JVM_HEAP_MAX` but you shouldn't need to adjust them thanks to the new defaults.
* Overhaul HTTP headers:
  * `l5d-ctx` renamed to `l5d-ctx-trace`
  * `l5d-ctx-deadline` now propagates deadlines
  * `l5d-ctx-dtab` is now read, to replace `dtab-local` later.
  * `l5d-dtab` now honored as a replacement for `dtab-local` as
    specified by users.
  * `l5d-dst-*` no longer set on responses
* Fix graceful connection teardown on streaming HTTP responses #482.
* linkerd routers' `timeoutMs` configuration now applies on the
  server-side, so that the timeout acts as a global timeout rather
  than an individual request timeout.
* Binding cache size is now configurable in linkerd and namerd
* Use :: as the zk host delimiter in the zk leader namer
* Admin site/dashboard UI improvements:
  * The linkerd dtab UI now works correctly with the namerd interpreter
  * Added server success rate graphs to the dashboard, improved responsiveness
  * Added the ability to navigate to a specific router's dashboard
  * Standardized the look and feel of the admin pages

## 0.6.0

* Add zkLeader namer to allow discovery of services through ZooKeeper leader
  election.
* Add HTTP path request identifier, which builds destinations from a
  configurable number of HTTP URI path segments.
* **Breaking Change!** The path prefix `/#` now indicates that the path should
  be processed by a namer.  A namer matches a path starting with `/#/<prefix>`.
* **Breaking Change!** Rename many plugin kind names.
* **Breaking Change!** Experimental plugins now require the `experimental: true`
  property to be set.
* **Breaking Change!** Change the format for ZooKeeper hosts in the ZK storage
  config.

## 0.5.0

* Add a `debugTrace` parameter to the `tracers` config section, which enables
  printing all traces to the console.
* Add etcd backed dtab storage.
* Introduce a default HTTP response classifier so that 5XX responses
  are marked as failures.
* Add a `retries` client config section supporting configurable retry
  budgets and backoffs.
* Automatically retry certain types of failures, as determined by
  response classifiers.
* Remove TLS support from the k8s namer in favor of using `kubectl proxy` for
  securely communicating with the k8s cluster API.
* Add an `/admin/metrics/prometheus` stats endpoint.

## 0.4.0

* Add a `bindingTimeoutMs` router parameter to configure the maximum amount of
  time to spend binding a path.
* Add experimental support for storing dtabs in Kubernetes via the
  ThirdPartyResource API (which must be enabled in your cluster).
* **Breaking api change** in namerd: dtabs are now string-encoded
  rather than thrift-encoded.
* Add `/api/1/bind`, `/api/1/addr`, and `/api/1/delegate` HTTP APIs to namerd
  * Most HTTP APIs now support `?watch=true` for returning updates via a
    streaming response.
* Add ACL and authentication support to the ZooKeeper DtabStore.
* Support wildcards in dtabs!
* New linkerd dashboard is now enabled by default!! :chart_with_upwards_trend:

## 0.3.1

* Add beta version of linkerd dashboard version 2.0.  Try it out at
  `/dashboard` on the linkerd admin site. :chart_with_upwards_trend:
* Support Zipkin tracer configuration via config file, to enable automatic
  export of tracing data from linkerd to a Zipkin collector.
* namerd's HTTP dtab API now supports the HEAD and DELETE methods
* Tear-down address observations in namerd if a service is deleted

## 0.3.0

* Added :sparkles: namerd :sparkles: : a service for managing linkerd (and finagle)
  name delegation.
* **Breaking change** to configs: `httpUriInDst` is now specified under the
  `identifier` header (see linkerd/docs/config.md for add'l info)
* Add a `ttlMs` marathon namer config option to configure the polling
  timeout against the marathon API.
* Add a `enableProbation` config option for configuring a client's load balancer
  probation setting

## 0.2.1

* Configs may now include a `tracers` section with pluggable tracers (although
  we don't provide any out of the box just yet)
* `namers` configurations may now configure Namers or NameInterpreters
  to support richer namer behavior.
* Add a loadBalancer section to the client config where a load balancer can be
  specified and configured.  The load balancers that are currently supported are
  p2c, ewma, aperture, and heap.
* Add a config.json admin endpoint which re-serializes the parsed linkerd config.
* Add a `maxConcurrentRequests` config option to limit number of concurrent
  requests accepted by a server.
* Add a `hostConnectionPool` client config section to control the number of
  connections maintained to destination hosts.
* Add a `attemptTTwitterUpgrade` thrift client config option to control whether
  thrift protocol upgrade should be attempted.

## 0.2.0

* This release contains **breaking changes** to the configuration file format.
  linkerd config files are now a bit more explicit and less "magical",
  in the following ways:
  * Router configuration options can no longer be specified globally at the
    root level of the config file, but must be specified per-router.
  * All routers must now include a `servers` section; previously, a default
    server port would be used if none was provided.
* New `thriftProtocol` config option allows the thrift protocol to be
  specified. We currently support `binary` (default) and `compact`.
* Added traffic routing support for marathon apps with slashes in
  their ids.
* Resolved a browser-compatibility issue in the admin page for those not
  using the latest-and-greatest Chrome/Firefox/Safari.


## 0.1.0

* Introduce Marathon-backed service discovery, for routing traffic in Mesos.
* Add new boundPath client TLS module for per-service TLS authentication.
* Upgrade to Finagle 6.33, the latest and greatest in Finagle-based technology.

## 0.0.11

* TLS, for real this time.
* Configuration updates: config now includes a client section, where you can
  configure client-specific parameters.

## 0.0.10

* We now support end-to-end TLS! However, verification is currently limited to
  global certs. See  https://github.com/BuoyantIO/linkerd/issues/64 for more on
  the upcoming roadmap.
* Prep work for "transparent TLS". Look for this in upcoming releases.
* Prep work for being able to generate Docker images from the repo, in service
  of a glorious containerized future.
* Dashboard improvements! Now harder, faster, better, and also stronger!

## 0.0.9

* Include ZooKeeper ServerSet support, for real this time.

## 0.0.8

* Big new feature alert! We now have Zookeeper ServerSet support.
* Server-side TLS support! Stay tuned for more security features coming in
  future releases...
* Added CONTRIBUTING.md with Contributor License Agreement. We are ready to
  receive your honorable pull requests!
* New `thriftMethodInDst` config option to allow for routing based on thrift
  method names.
* Admin port now configurable via an `admin/port` config parameters, for those
  of you who have Opinions About Ports.
* DTab explorer admin page now supports inspecting DTabs for all configured
  routers.
* New `/routers.json` endpoint with runtime router state.
* We now have a [slack channel](http://slack.linkerd.io)! Operators are
  standing by to field YOUR questions today.
* Admin site redesign to match [linkerd.io](https://linkerd.io/), now with
  favicon!

## 0.0.7

This is a big release! Get ready.

* Brand new name: :sunrise: linkerd :balloon:
* We're open source! This release is under Apache License v2.
* Tons of documentation on https://linkerd.io!
* This release adds config file support! You can express all your routing,
  listening, and protocol configuration needs in one convenient YAML file! See
  docs for how this works.

## 0.0.6

* Admin UI now features 25% more amazingness. :rainbow:
* Preliminary "pure" thrift support.
  * Default is framed transport with binary encoding; buffered transport also
    supported.
  * Out of the box, the router is configured to listen for thrift on port 4141
    (i.e. in addition to HTTP on port 4140), and forwards thrift calls to
    localhost:9998. This will almost definitely change in the future.
* Tons of performance tuning. We're benchmarking sub-1ms p99 request latency and
  40k+ qps throughput. Working on memory footprint reduction next.
* By popular demand, HTTP response code stats are now exported in metrics.json.
* Configurability still limited to what you can change in config.sh and disco/.
  Expect improvements here soon.

## 0.0.5

* Fancy Request Volume graph in the Admin page.
* Hide some internal interfaces from the Admin page.
* Modified interface labels to work in twitter-server's admin.

## 0.0.4

* Experimental Mux protocol support, for Advanced Users Only.
* New Admin UI that tries to not look like it was built by engineers.

## 0.0.3

* Using sophisticated shell script technology, we now ensure you have a
  sufficient Java version (we require JDK 8) before attempting to start the
  router.
* Upgrades to a newer version of the
  [Finagle](http://twitter.github.io/finagle/) library.
* More information added to HTTP tracing:
  * Host header
  * Transfer-Encoding header
* New configuration options for HTTP routing
  * Routing by URI can be disabled, which simplifies many common use-cases
  * Allow internal and external http service prefixes to be specified on the
    command-line
* Fixed the "downstream clients" admin interface

## 0.0.2

* Router start/stop commands now detect if the router is currently running,
  easily preventing a whole class of easily-preventable errors.
* Tarball permissions are fixed.
* New support for Consul-backed service discovery, if files aren't good enough
  for ya.

## 0.0.1

First release of the Buoyant Application Router.

* Complete with `router` script to start/stop/restart the router!
* Router is pre-configured with sane defaults for running locally.
* Filesystem-backed service discovery mechanism.
