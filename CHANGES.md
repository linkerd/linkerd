## In the next release...

## 0.8.4 2016-12-05

* Change default value of `attemptTTwitterUpgrade` to `false`
* The `io.l5d.consul` and `io.l5d.k8s` namers are no longer experimental 🎉
* H2 stabilization:
  * Fix the `h2` protocol to gracefully handle connection loss and
    stream interruption.
  * RFC-compliant handling of connection-specific headers.
  * Routing failures are now surfaced as REFUSED_STREAM resets.
* Add per-logical-destination stats to each concrete client.
* Add `io.l5d.static` identifier
* Fix a config-serialization issue that prevented the Dtab Admin UI
  from working properly with some configurations.

## 0.8.3 2016-11-07

* Make several namers available to namerd that were missing
* Fix crash when viewing the dtab playground
* Announce to all routable addresses when announcing 0.0.0.0
* Add experimental Apache Curator namer
* Marathon:
  * Add authentication support to marathon namer
  * Add `useHealthCheck` option to marathon namer
* Transformers:
  * Allow transformers to be applied to namers
  * Add Const and Replace transformers
  * Show transformers in the delegate UI
* Kubernetes:
  * Add `labelSelector` option to k8s and k8s.external namers
  * Add `hostNetwork` option to k8s transformers to support CNI environments

## 0.8.2 2016-10-17

* Consul namer can use `.local` to reference local agent's datacenter.
* Add an `ip` option to admin configuration so that access to the
  admin server may be constrained.
* Kubernetes integration:
  * Remove unused TLS options from the k8s storage plugin config.
  * Add k8s external namer for routing to k8s ingress services.
  * Improve error-handling behavior in k8s API clients.
* Support serving the namerd namer interface over TLS.
* Document namerd's HTTP API.
* Improve retry metrics to include a total counter of all retry requests.
* Fix a path-parsing bug in the io.l5d.path namer.
* Provide a default log4j configuration so that netty logging is managed properly.
* Improve HTTP server behavior with short-lived connections.
* Add `io.buoyant.rinet` namer which is like `inet` but with the order
  of host and port reversed
* The `netty4` HTTP engine now works with TLS, supporting configurable
  ciphers, backed by BoringSSL!
* Introduce experimental support for the `h2` protocol, supporting gRPC! :balloon:

## 0.8.1 2016-09-21

* Fix missing data on the linkerd admin dashboard
* Allow a non-default port to be specified for the etcd storage plugin

## 0.8.0 2016-09-20

* Allow routers to be configured with a list of identifiers.  If an identifier
  cannot assign a dest to a request, it falls back to the next one in the list.
  * **Breaking Change**: Identifier plugins must now return a
    `RequestIdentification` object.
* Consul improvements:
  * Improve performance by only watching services as necessary and tearing
    down watches when they are no longer needed.
  * Add `consistencyMode` option to `io.l5d.consul` namer
  * Add `readConsistencyMode` and `writeConsistencyMode` options to
    `io.l5d.consul` dtab storage
  * Consul Namerd/DtabStore: `failFast` and `failureAccrual` is now
    disabled by default but can be enabled with the `failFast` option
* Improve shutdown ordering to facilitate graceful shutdown.
  * Gracefully shutdown on SIGINT and SIGTERM.
* Require tracer configuration instead of falling back to
  defaults, reducing logging noise.
* **Breaking Change**: The `debugTrace` tracer configuration flag has been
  removed in favor of the `io.l5d.tracelog` telemeter.
* Add `io.l5d.header` identifier for naming requests based on an HTTP header
* Lowercase `Host` header value in `io.l5d.methodAndHost` identifier
* Introduce transformers for post-processing the set of addresses returned by
  an interpreter.
  * Add k8s transformers to support linkerd-to-linkerd deployments when linkerd
    is deployed as a k8s daemonset.
* Remove hop-by-hop headers for better HTTP proxy compliance


## 0.7.5

* Beautiful new linkerd docs!!! :heart_eyes: https://linkerd.io/config/0.7.5/linkerd
* HTTP response classifiers must not consider a request to be
  retryable when it has a chunked request body.
* Fix query paramater encoding when rewriting proxied requests
* Improve error handling and retry behavior of consul plugins.
* Add `useHealthCheck` parameter to Consul Namer #589
* The k8s namer will now resume watches if the connection is closed.
* Improved the performance of the namerd HTTP API.
* Configured namers are now available to other plugins
* `enableProbation` is now disabled by default on clients. It leads to
  unexpected behavior in environments that reuse IP:PORT pairs across
  services in a close time proximity.

## 0.7.4

* Dashboard: add toggling to the router clients to better handle large numbers of clients
* namerd HTTP API:
  * Add `resolve` endpoint
  * All endpoints return json
* Add `authority` metadata field to re-write HTTP host/:authority on demand
* Consul improvements:
  * Add `setHost` parameter for Consul CatalogNamer to set `authority` metadata
  * Add auth `token` parameter to Consul Namer & Dtab Store
  * Add `datacenter` parameter to Consul Dtab Store
* Add file-system based name interpreter.
* Path identifier should only parse as many segments as requested
* Introduce the _telemetry_ plugin subsystem to support arbitrary stats
  exporters and to eventually supplant the `tracers` subsystem.
* Add announcer support! linkerd can now announce to service discovery backends!
  * Add zk announcer.

## 0.7.3

* Allow protocol-specific parameters to be inherited on servers #561.
* Don't clear addr on k8s service deletion #567.
* Modify namerd's `/delegate` http endpoint to return bound names #569.
* Memoize status stats components #547.

## 0.7.2

* Add support for tags in the `io.l5d.consul` namer.
* Add an experimental `io.l5d.consul` storage backend for namerd.
* linkerd should use last known good data if it get errors from namerd.
* Fix exceptions when k8s namer encounters unexpected end of stream #551.
* Expose HTTP codec parameters as configuration options.
* Handle "too old" error when re-establishing Kubernetes watches.
* Improve Java compatibility for Namers plugins.

## 0.7.1

* Turn off HTTP decompression so that linkerd doesn't decompress and then
  recompress bodies.
* Various bug fixes in the dtab UI
* Optional dtab query parameter for selected Namerd HTTP Control API endpoints
* Fix an issue where streaming was unintentionally disabled
* Fix an issue with the io.l5d.serversets namer and residuals
* Add a `consume` option to the `io.l5d.path` identifier to strip off the path
  segments that it reads from the URI.
* Introduce a configurable Netty4 http implementation.

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
