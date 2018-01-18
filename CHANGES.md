## 1.3.5 2018-01-17

* 🎓 H2 router and `io.l5d.mesh` Namerd interface are no longer experimental ([#1782](https://github.com/linkerd/linkerd/pull/1782))! 🎓
* Add an experimental namer for Rancher service discovery ([#1740](https://github.com/linkerd/linkerd/pull/1740)). A huge thank you to [@fangel](https://github.com/fangel) for contributing this namer!
* Kubernetes
  * Fix a bug that could cause the `io.l5d.k8s` namer to get "stuck" and fail to recieve updates from an endpoint ([#1755](https://github.com/linkerd/linkerd/pull/1755)). Contributed by [@obeattie](https://github.com/obeattie).
* Admin UI
  * Add `/admin/client_state.json` endpoint to return the current address set of each client ([#1768](https://github.com/linkerd/linkerd/pull/1768)).
  * Fix an error when using the admin UI to perform delegations with a dtab stored in Namerd over the `io.l5d.thriftNameInterpreter` interface ([#1762](https://github.com/linkerd/linkerd/pull/1762)). Thanks to [@jackkleeman](https://github.com/jackkleeman)!
  * Render an error instead of a blank page for failures on Namerd's dtab playground ([#1770](https://github.com/linkerd/linkerd/pull/1770)).
* Namerd
  * Errors parsing dtabs stored in Consul are now surfaced in log messages ([#1760](https://github.com/linkerd/linkerd/pull/1760)).
  * Fix an error where Linekrd could sometimes miss updates from Namerd when using the `io.l5d.thriftNameInterpreter` interface ([#1753](https://github.com/linkerd/linkerd/pull/1753)). Thanks to [@obeattie](https://github.com/obeattie)!

## 1.3.4 2017-12-15

Linkerd 1.3.4 continues the focus on reliability and stability. It includes a bugfix for HTTP/2 and gRPC routers, several improvements to the Consul namer and dtab store, fixes for 4xx responses in the Kubernetes namer, and more.

* Fix an issue where the `io.l5d.path` identifier would consume query parameters from the request URL, preventing them from reaching the downstream service ([#1734](https://github.com/linkerd/linkerd/pull/1734)).
* Several minor fixes to documentation and examples.
* Consul
  * Improve handling of invalid namespaces in Namerd's Consul dtab store ([#1739](https://github.com/linkerd/linkerd/pull/1739)).
  * Add backoffs to Consul dtab store observation retries ([#1742](https://github.com/linkerd/linkerd/pull/1742)).
  * Fix `io.l5d.consul` namer logging large numbers of spurious error messages during normal operation ([#1738](https://github.com/linkerd/linkerd/pull/1738)).
* HTTP/2 and gRPC
  * Fix buffer data corruption regression introduced in 1.3.3 ([#1751](https://github.com/linkerd/linkerd/pull/1751)). Thanks to [@vadimi](https://github.com/vadimi), who contributed to this fix!
* Kubernetes
  * Improve handling of Kubernetes API watch errors in `io.l5d.k8s` ([#1744](https://github.com/linkerd/linkerd/pull/1744), [#1752](https://github.com/linkerd/linkerd/pull/1752)).
* Namerd
  * Fix `NoHostsAvailable` exception thrown by `io.l5d.mesh` when Namerd has namers configured with transformers ([#1729](https://github.com/linkerd/linkerd/pull/1729)).

## 1.3.3 2017-12-01

:rotating_light: Bugfix extravanganza alert! :rotating_light:

This release is an exciting one! We received a lot of contributions from our awesome Linkerd community. Special thanks
to [@sgrankin](https://github.com/sgrankin) and [@carloszuluaga](https://github.com/carloszuluaga), just to name a few. Checkout our recent blog post for the full list of everyone that contributed
to release 1.3.3.
* Fix a bug where Namerd using `io.l5d.etcd` as a dtab store returns a 500 HTTP Response when listing DTabs. [(#1702)](https://github.com/linkerd/linkerd/pull/1702)
* Fix an HTTP/2 memory leak where a Netty ByteBuf is not properly released. [(#1711)](https://github.com/linkerd/linkerd/pull/1711)
* Fix a bug where certain namers do not extend the admin UI. Shoutout to [@robertpanzer](https://github.com/robertpanzer) for finding and fixing this bug! [(#1717)](https://github.com/linkerd/linkerd/pull/1717)
* Fix an `AsyncStream` memory leak in the Kubernetes watch API. Thanks [@sgrankin](https://github.com/sgrankin) for this PR! [(#1714)](https://github.com/linkerd/linkerd/pull/1714)
* Fix a bug where the `io.l5d.dnssrv` namer plugin does not always update DNS records. Awesome contribution from [@carloszuluaga](https://github.com/carloszuluaga) [(#1719)](https://github.com/linkerd/linkerd/pull/1719)

## 1.3.2 2017-11-16
* Kubernetes
  * Deprecate ThirdPartyResources in favor of CustomResourceDefinitions for Namerd dtab storage (#1688)
* Thrift
    * Fix a bug with the Thrift Identifier not properly identifying TTwitterThrift requests
* Consul
    * Add the ability to weight addresses based on Consul tags
* Namerd
    * Fix a memory leak involving the `io.l5d.thriftNameInterpreter` interface
    * Fix a bug where a Namerd client could sometimes becomes unresponsive
* gRPC
    * Fix a bug where large message bodies in unary gRPC requests could cause Linkerd to hang
* TLS
    * Add `forwardClientCert` to HTTP and HTTP/2 client configurations which causes Linkerd to forward client TLS certificates in the x-forwarded-client-cert header

## 1.3.1 2017-10-24

* Kubernetes
  * Fixed a failure to update routing data after restarting watches (#1674).
  * Ensured that Kubernetes API watch events earlier than the current state are ignored (#1681).
* Added support for Istio Mixer precondition checks (#1606).
* Removed spurious error message logging from Consul namer (#1682).
* Changed DNS SRV record namer to use system DNS resolver configuration (#1679).
* Added `timestampHeader` configuration to support New Relic request queue (#1672).


## 1.3.0 2017-10-06

* **Breaking Change**: All HTTP engines are now Netty 4; `engine:` configuration key is no longer valid.
* Upgraded to Finagle 7.1
* Kubernetes
  * Added a workaround for an issue where Kubernetes namers fail to update because watches are not correctly restarted due to a regression in some versions of Kubernetes (#1636).
  * Fixed `io.l5d.k8s.configMap` interpreter failing to update after receiving an invalid dtab (#1639).
  * Performance improvements for Kubernetes namers.
* Prometheus
  * Added an optional `prefix:` configuration key to add a prefix to all metrics reported by Linkerd (#1655).
* DNS SRV Record namer
  * Ensured that DNS names in SRV queries are absolute (#1637).
  * Added an optional `domain` config key for relative DNS lookups (#1637).
  * Removed redundant `dnssrv` metrics scope from SRV record namer metrics (#1637).
* Consul
  * Consul namers no longer watch the entire list of services, improving performance significantly when there are large numbers of services (#1646).
* Curator
  * Added support for `ServiceInstance` objects with custom payloads (#1272).


## 1.2.1 2017-09-14

Fix for an issue where Kubernetes namers would continue to route to old
endpoints after a service was deleted and re-created, or scaled down to 0 and
then scaled back up.

Also includes:
* The path on which the Prometheus telemeter serves metrics can now be
  set in the config file.
* Minor documentation fixes.


## 1.2.0 2017-09-07

* **Breaking Change**: `io.l5d.mesh`, `io.l5d.thriftNameInterpreter`, Linkerd
  admin, and Namerd admin now serve on 127.0.0.1 by default (instead of
  0.0.0.0).
* **Breaking Change**: Removed support for PKCS#1-formatted keys. PKCS#1 formatted keys must be converted to PKCS#8 format.
* Added experimental `io.l5d.dnssrv` namer for DNS SRV records (#1611)
* Kubernetes
  * Added an experimental `io.l5d.k8s.configMap` interpreter for reading dtabs from a Kubernetes ConfigMap (#1603). This interpreter will respond to changes in the ConfigMap, allowing for dynamic dtab updates without the need to run Namerd.
  * Made ingress controller's ingress class annotation configurable (#1584).
  * Fixed an issue where Linkerd would continue routing traffic to endpoints of a service after that service was removed (#1622).
  * Major refactoring and performance improvements to `io.l5d.k8s` and `io.l5d.k8s.ns` namers (#1603).
  * Ingress controller now checks all available ingress resources before using a default backend (#1607).
  * Ingress controller now correctly routes requests with host headers that contain ports (#1607).
* HTTP/2
  * Fixed an issue where long-running H2 streams would eventually hang (#1598).
  * Fixed a memory leak on long-running H2 streams (#1598)
  * Added a user-friendly error message when a HTTP/2 router receives a HTTP/1 request (#1618)
* HTTP/1
  * Removed spurious `ReaderDiscarded` exception logged on HTTP/1 retries (#1609)
* Consul
  * Added support for querying Consul by specific service health states (#1601)
  * Consul namers and Dtab store now fall back to a last known good state on Consul observation errors (#1597)
  * Improved log messages for Consul observation errors (#1597)
* TLS
  * Removed support for PKCS#1 keys (#1590)
  * Added validation to prevent incompatible `disableValidation: true` and `clientAuth` settings in TLS client configurations (#1621)
* Changed `io.l5d.mesh`, `io.l5d.thriftNameInterpreter`, Linkerd
  admin, and Namerd admin to serve on 127.0.0.1 by default (instead of
  0.0.0.0) (#1366)
* Deprecated `io.l5d.statsd` telemeter.


## 1.1.3 2017-08-09

The 1.1.3 release of Linkerd is mostly focused on improving our HTTP/2 support,
including better support for gRPC.  Linkerd now supports automatic retries in
HTTP/2 for retryable requests.

* HTTP/2
  * Cleaned up spurious errors messages in the Linkerd log output.
  * Added a number of gRPC response classifiers that use the `grpc-status` code
    to determine if the response was successful and if it should be retried.
    See [the docs](https://linkerd.io/config/1.1.3/linkerd/index.html#grpc-response-classifiers) for details.
  * Added support for failure accrual and automatic retries to HTTP/2.
  * Fixed a memory leak related to messages with only a headers frame.
* Istio
  * Added HTTP/2 support to the Istio integration: the `io.l5d.k8s.istio`
    identifier can now be used in H2 router configs.
  * Added support for HTTPRedirect Route Rules.
* The Linkerd and Namerd admin sites can now be configured to require HTTPS.

## 1.1.2 2017-07-12

* Marathon Namer TLS support, for DC/OS strict mode.
* We fixed an issue where requests that time out were not being retried.
* HTTP 1.1 protocol fixes for chunked transfer encoding and `Content-Length`.
* Improved memory allocation in InfluxDb and Prometheus telemeters.
* Documentation fixes.

## 1.1.1 2017-07-10

This is a big release with lots of fun stuff inside.

We've added some new features!
* Linkerd now features integration with Istio! (Beta.) This is a big feature. Blog post coming soon.
* We've introduced a new request logger plugin interface, for plugins that take an action (such as logging) on each request. This is currently used by the Istio plugin to report metadata about each request.

We’ve fixed some things!
* We fixed a connection leak in HTTP/2 by properly multiplexing streams over a single connection.
* The configured failure accrual backoff parameter was being ignored. Now it's not!
* We fixed a TLS issue when no trust certs were specified.  As a result, using TLS with egress now works again.
* We fixed an exception when a Kubernetes Service's `targetPort` value is returned as a name instead of a number.
* The admin dashboard now displays server connections, standardizing client and server displays.

We’ve made some internal changes to keep up with the latest and greatest:
* Netty4 is now the default engine for HTTP.
* We’ve upgrade to Finagle 6.45 under the hood.

## 1.1.0 2017-06-12

* TLS
  * Add support for client auth TLS.
  * Add TLS support for `io.l5d.httpController` and `io.l5d.mesh` Namerd
    interfaces.
* HTTP/2
  * Reset h2 remote streams that continue to send frames after the local stream
    has been interrupted.  This fixes a bug that occationally caused the
    io.l5d.mesh interpreter to hang.
  * Add support for HTTP/2 tracing.
* Kubernetes
  * Fix exception when a loadBalancer object has a hostname instead of an ip.
  * Fix connection leak when the daemonset transformer cannot connect to the k8s
    API.
* Metrics
  * Improve scoping of metrics for namers and transformers.
  * Fix rendering of top-level influx metrics.
* Consul
  * Cache dtab observations in the io.l5d.consul store.
  * Fix bug causing consul queries to hang.
* Expire idle services and clients.
* **Breaking Change**: Convert `thriftProtocol` from a client/server param to a
  router param.

## 1.0.2 2017-05-12

* Fix issue where TLS could not be used with H2.
* Fix Linkerd admin dashboard edge case.

## 1.0.1 2017-05-12

* Upgrade to scala 2.12.
* Upgrade to finagle 6.44.
* HTTP/1.1:
  * Fix connection leak when retrying on responses with chunked bodies.
  * Remove Linkerd headers and body when clearContext is set.
  * Add io.l5d.http.allSuccessful and io.l5d.h2.allSuccessful response classifiers.
* HTTP/2:
  * Fix race condition causing every request on a connection to deadline.
  * Fix memory leak related to tracking closed streams.
* Kubernetes:
  * Port numbers in k8s names will now have the service's port mapping applied.
  * Add `io.l5d.k8s.ns` namer for routing within a fixed namespace.
* Consul:
  * Fix issue where the Consul namer would fail to reconnect after ConnectionFailedException.
* Promethus:
  * Properly escape metrics labels in the Prometheus telemeter.
* Namerd:
  * Add support for telemeters.
* Fail on duplicate config file properties instead of silently taking the last
  value.
* Add path stack registry for better visibility into how services are configured.

## 1.0.0 2017-04-24

* Configuration:
  * Add support for per-client configuration.
  * Add support for per-service configuration.
  * Simplify TLS configuration.
  * Split the timeoutMs router option into a requestAttemptTimeoutMs client option
    and a totalTimeoutMs service option.
  * Rename the "dst/path" metrics scope to "service".
  * Rename the "dst/id" metrics scope to "client".
  * Rename the "namer.path" trace annotation to "service".
  * Rename the "dst.id" trace annotation to "client".
  * Rename the "dst.path" trace annotation to "residual".
  * Rename the "l5d-dst-logical" HTTP and H2 headers to "l5d-dst-service".
  * Rename the "l5d-dst-concrete" HTTP and H2 headers to "l5d-dst-client".
  * Rename the "srv" metrics scope to "server".
  * Encode retryability on HTTP responses in the `l5d-retryable` header.
  * Rename http response classifiers to be protocol specific:
    * The `io.l5d.nonRetryable5XX` id has been renamed to `io.l5d.http.nonRetryable5XX`.
    * The `io.l5d.retryableRead5XX` id has been renamed to `io.l5d.http.retryableRead5XX`.
    * The `io.l5d.retryableIdempotent5XX` id has been renamed to `io.l5d.http.retryableIdempotent5XX`.
  * Refactor http and h2 identifiers for consistency:
    * The `io.l5d.headerToken` id has been renamed to `io.l5d.header.token`.
    * The `io.l5d.headerPath` id has been renamed to `io.l5d.header.path`.
    * The `io.l5d.h2.ingress` id has been renamed to `io.l5d.ingress`.
    * The `io.l5d.http.ingress` id has been renamed to `io.l5d.ingress`.
* The following plugins are no longer experimental:
  * Marathon namer
  * Consul dtab store
  * K8s dtab store
  * Zk dtab store
* Fix h2 memory leak in Netty4DispatcherBase.
* Greatly reduced docker image size.
* Add `io.l5d.influxdb` LINE telemeter.
* Experimental ThriftMux protocol support.
* Automatically upgrade all HTTP/1.0 messages to HTTP/1.1.
* Allow dtab fallback when consul returns an empty address set.
* Fixed k8s namer to handle null endpoint subsets.
* Add support for Marathon HTTP basic authentication,

## 0.9.1 2017-03-15

* Admin dashboard:
  * Fix display issues for long dtabs in the Namerd tab.
  * Indicate the primary path in the dtab tab.
  * Add `tree` and `q` params to /admin/metrics.json.
* Kubernetes:
  * Allow k8s namer to accept port numbers.
  * Make k8s namer case insensitive.
  * Add k8s ingress identifiers to allow Linkerd to act as an ingress controller.
* Fix TTwitter thrift protocol upgrade bug.
* Rewrite Location & Refresh HTTP response headers when Linkerd
  rewrites request Host header.
* Increase default binding cache size to reduce connection churn.
* Fetch correct protoc version on demand.
* Introduce the `io.l5d.mesh` Linkerd interpreter and Namerd iface. The mesh
  iface exposes a gRPC API that can be used for multiplexed, streaming updates.
  (*Experimental*)

## 0.9.0 2017-02-22

* Admin dashboard:
  * Add retries stat, retry budget bar, and client pool bar.
  * Add colored border to clients to make them easier to distinguish.
  * Sorts clients and servers alphabetically.
  * Displays routers in the order that they are defined.
  * Namerd Admin now works with Dtabs of arbitrary size.
* Naming and Routing:
  * Rename `baseDtab` router property to `dtab`.
  * Change the default `dstPrefix` from the protocol name to `/svc`.
  * Change the default HTTP identifier to the `io.l5d.header.token` identifier.
  * Add the ability to route basted on the `dest` request header when using the
    TTwitter Thrift protocol.
* Metrics and Tracing:
  * Remove `io.l5d.commonMetrics` telemeter.
  * Add `io.l5d.prometheus` telemeter.
  * Remove the `tracers` router config in favor of the `io.l5d.zipkin` telemeter.
  * Add opt-out usage data collection.
* Namers:
  * Update Marathon namer to evaluate an app's running state.
  * Add `preferServiceAddress` option to `io.l5d.consul` namer
  * Make `io.l5d.consul` case-insensitive
* Add `roundRobin` as a load balancer option.
* Add the `clearContext` server configuration option.
* Fix query parameter decoding when rewriting proxied requests

## 0.8.6 2017-01-19

* Add experimental StatsD telemeter
* Admin dashboard
  * Add a log of recent requests
  * Now works if served at a non-root url
* HTTP
  * Support the RFC 7329 `Forwarded` header
* HTTP/2
  * H2 clients now properly advertise support for the “http2” protocol over
    ALPN
* Introduce `io.buoyant.hostportPfx` and `io.buoyant.porthostPfx` namers for
  splitting port numbers out of hostnames
* Add the `io.l5d.rewrite` namer for arbitrary reordering of path segments
* Bug fixes:
  * Fix path identifier bug when slash precedes uri params
  * Fix subdomainOfPfx handling of hostnames with port numbers

## 0.8.5 2017-01-06

* Introduce the grpc-gen and grpc-runtime projects, enabling code
  generation of gRPC clients and servers for Finagle.
* Various bug fixes to the Linkerd admin dashboard.
* The default docker images now use a 64 bit JVM.  A `-32b` docker image is
  also availble but does not support the boringssl TLS extensions required for
  ALPN, etc.
* Marathon:
  * Support "ip per task" feature
* Client failure accrual is now configurable via the `failureAccrual` parameter
* Add `io.l5d.namerd.http` interpreter which uses Namerd's streaming HTTP api
* Linkerd now writes the local dtab to the `l5d-ctx-dtab` header instead of
  `dtab-local`
* Transformers:
  * Transformers will now prepend a prefix to the id of the bound names they
    modify.
  * Fix localhost transformer when used on systems with unresolvable hostname.

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

## 0.8.3 2016-11-07

* Make several namers available to Namerd that were missing
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
* Support serving the Namerd namer interface over TLS.
* Document Namerd's HTTP API.
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

* Fix missing data on the Linkerd admin dashboard
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
  * Add k8s transformers to support linker-to-linker deployments when Linkerd
    is deployed as a k8s daemonset.
* Remove hop-by-hop headers for better HTTP proxy compliance


## 0.7.5

* Beautiful new Linkerd docs!!! :heart_eyes: https://linkerd.io/config/0.7.5/linkerd
* HTTP response classifiers must not consider a request to be
  retryable when it has a chunked request body.
* Fix query paramater encoding when rewriting proxied requests
* Improve error handling and retry behavior of consul plugins.
* Add `useHealthCheck` parameter to Consul Namer #589
* The k8s namer will now resume watches if the connection is closed.
* Improved the performance of the Namerd HTTP API.
* Configured namers are now available to other plugins
* `enableProbation` is now disabled by default on clients. It leads to
  unexpected behavior in environments that reuse IP:PORT pairs across
  services in a close time proximity.

## 0.7.4

* Dashboard: add toggling to the router clients to better handle large numbers of clients
* Namerd HTTP API:
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
* Add announcer support! Linkerd can now announce to service discovery backends!
  * Add zk announcer.

## 0.7.3

* Allow protocol-specific parameters to be inherited on servers #561.
* Don't clear addr on k8s service deletion #567.
* Modify Namerd's `/delegate` http endpoint to return bound names #569.
* Memoize status stats components #547.

## 0.7.2

* Add support for tags in the `io.l5d.consul` namer.
* Add an experimental `io.l5d.consul` storage backend for Namerd.
* Linkerd should use last known good data if it get errors from Namerd.
* Fix exceptions when k8s namer encounters unexpected end of stream #551.
* Expose HTTP codec parameters as configuration options.
* Handle "too old" error when re-establishing Kubernetes watches.
* Improve Java compatibility for Namers plugins.

## 0.7.1

* Turn off HTTP decompression so that Linkerd doesn't decompress and then
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
* Linkerd routers' `timeoutMs` configuration now applies on the
  server-side, so that the timeout acts as a global timeout rather
  than an individual request timeout.
* Binding cache size is now configurable in Linkerd and Namerd
* Use :: as the zk host delimiter in the zk leader namer
* Admin site/dashboard UI improvements:
  * The Linkerd dtab UI now works correctly with the Namerd interpreter
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
* **Breaking api change** in Namerd: dtabs are now string-encoded
  rather than thrift-encoded.
* Add `/api/1/bind`, `/api/1/addr`, and `/api/1/delegate` HTTP APIs to Namerd
  * Most HTTP APIs now support `?watch=true` for returning updates via a
    streaming response.
* Add ACL and authentication support to the ZooKeeper DtabStore.
* Support wildcards in dtabs!
* New Linkerd dashboard is now enabled by default!! :chart_with_upwards_trend:

## 0.3.1

* Add beta version of Linkerd dashboard version 2.0.  Try it out at
  `/dashboard` on the Linkerd admin site. :chart_with_upwards_trend:
* Support Zipkin tracer configuration via config file, to enable automatic
  export of tracing data from Linkerd to a Zipkin collector.
* Namerd's HTTP dtab API now supports the HEAD and DELETE methods
* Tear-down address observations in Namerd if a service is deleted

## 0.3.0

* Added :sparkles: Namerd :sparkles: : a service for managing Linkerd (and finagle)
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
* Add a config.json admin endpoint which re-serializes the parsed Linkerd config.
* Add a `maxConcurrentRequests` config option to limit number of concurrent
  requests accepted by a server.
* Add a `hostConnectionPool` client config section to control the number of
  connections maintained to destination hosts.
* Add a `attemptTTwitterUpgrade` thrift client config option to control whether
  thrift protocol upgrade should be attempted.

## 0.2.0

* This release contains **breaking changes** to the configuration file format.
  Linkerd config files are now a bit more explicit and less "magical",
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
  global certs. See  https://github.com/linkerd/linkerd/issues/64 for more on
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

* Brand new name: :sunrise: Linkerd :balloon:
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
