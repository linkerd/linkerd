## 1.7.0 2019-08-27
Linkerd 1.7.0 includes a number of memory leak fixes for Linkerd and its
underlying `grpc-runtime` module. This release includes improvements for
SNI-enabled TLS communication, support for streaming arbitrarily large HTTP
requests and responses in HTTP/1 and HTTP/2 as well an upgraded JDK for
improved Docker container support.

A special thank you to [Fantayeneh](https://github.com/fantayeneh) for their
awesome work on [#2315](https://github.com/linkerd/linkerd/pull/2315)

Full release notes:
* **Breaking Change**
  * Removes `maxRequestKB` and `maxResponseKB` from Linkerd's configuration
    options in favor of `streamAfterContentLengthKB`. These parameters were
    primarily intended to limit the amount of memory Linkerd used when buffering
    requests. The streamAfterContentLengthKB parameter achieves this more
    efficiently by streaming large messages instead of buffering them.

* Consul
  * Enables streaming in the HTTP client used in the `io.l5d.consul` namer
    to allow for arbitrarily large responses from Consul
  * Support for the inclusion of Consul response service and node metadata
    in Namerd 'io.l5d.mesh and 'io.l5d.httpController' responses.
* Linkerd Configuration
  * Introduces a router parameter called `maxCallDepth` that prevents unbounded
    cyclic proxy request routing
  * Adds support for limiting the maximum size of `l5d-err` header values by
    using `maxErrResponseKB` in an HTTP router
  * Fixes an issue were some `socketOptions` were being ignored when partially
    configured
* TLS
  * Fixes an issue where Linkerd can't connect to SNI servers that are addressed
    via IPv4 and IPv6
* HTTP/2
  * Fixes a number of direct and heap memory leaks in Linkerd's HTTP/2 module
  * Fixes an issue causing users of `grpc-runtime` module to experience direct
    memory leaks
* Updates Linkerd's JDK version for improved container support

## 1.6.4 2019-07-01
Linkerd 1.6.4 updates the finagle version to 19.5.1 and adds support for
configuring message response sizes from when using consul.  
              
ConsulInitializer.scala now includes the parameters below which are used 
to configure the Http.client object that is instantiated in the `newNamer` 
method.
              
Parameter Name | Default Value | Description
-----------------|---------------|-------------
`maxHeadersKB` | 8 | The maximum size of all headers in an HTTP message created by the Consul client
`maxInitialLineKB` | 4 | The maximum size of an initial HTTP message line created by the Consul client
`maxRequestKB` | 5120 | The maximum size of a non-chunked HTTP request payload sent by the Consul client
`maxResponseKB` | 5120 | The maximum size of a non-chunked HTTP response payload received by the Consul client
              
Full release notes:
* Upgrade to finagle 19.5.1 [#2284](https://github.com/linkerd/linkerd/pull/2284)
* Support configurable response body sizes from consul [#2286](https://github.com/linkerd/linkerd/issues/2286) 
* Log inbound requests to namerd [#2275](https://github.com/linkerd/linkerd/pull/2275) 

## 1.6.3 2019-05-10
Linkerd 1.6.3 includes a bug fix for Namerd's `io.l5d.k8s` dtab storage module. This fix exposes
HTTP request and response metrics for the Kubernetes API client used to store dtabs. Namerd also
includes a new logging dashboard page that allows you to change Namerd's logging verbosity at
runtime. In addition, this release improves Linkerd's HTTP/2 implementation to better manage direct
memory and includes a fix for Linkerd's `interpreter_state` watch state endpoint.

A special thank you to the following contributors for their awesome doc update contributions:
* [Nguyen Hai Truong](https://github.com/truongnh1992)
* [Peter Fröhlich](https://github.com/peterfroehlich)

Full release notes:
* Namerd
  * Expose HTTP client metrics for Namerd's `io.l5d.k8s` dtab storage module. This change now
    instruments the HTTP client that interacts with the Kubernetes API used for storing dtabs.
  * Adds a new logging dashboard page in Namerd with the ability to change logging verbosity at
    runtime. This is similar to Linkerd's logging dashboard page
* HTTP/2
  * Fixes an issue where Linkerd could sometimes exhaust direct memory when routing HTTP/2 requests
  that immediately receive a `RST_STREAM` frame.
* Fixes a `BufferUnderflowException` that could be triggered when viewing Linkerd's
  interpreter watch state endpoint. This issue occurs when using Linkerd's `io.l5d.namerd` thrift
  interpreter.
* Fixes an issue where Linkerd incorrectly uses the JVM's Parallel GC collector if the `GC_LOG`
  start-up flag is not set on JVMs using Java 1.8 or earlier.

## 1.6.2 2019-03-08
This Linkerd release includes bug fixes for Namerd's k8s watch API as well as memory management
improvements in the `io.l5d.zk` storage plugin. This release features a new failure detector
module in the `io.l5d.mesh` interpreter that sends "heartbeat" pings on all HTTP/2 connections to
Namerd. This is intended to monitor the health of connections between Linkerd and Namerd so that
connections are torn down and re-established if a ping message is not received within a configured
amount of time.

This release also includes doc updates from the following contributors:
* [LongKB](https://github.com/longkb)
* [Nguyen Phuong An](https://github.com/annp1987)
* [JoeWrightss](https://github.com/JoeWrightss)
* [tuanvcw](https://github.com/tuanvcw)
* [Nguyen Hai Truong](https://github.com/truongnh1992)

A big shoutout to all contributors listed above for their great work!

Full release notes:

* OpenJ9
  * Fixes an issue in Linkerd's OpenJ9 docker image where Linkerd may sometimes run into an
  `OutOfMemoryErrorException` caused by OpenJ9's JDK base image
* Namerd
  * Fixes a memory leak in the `io.l5d.zk` dtab storage module
  * Fixes an issue where Namerd stops watching dtabs if it receives HTTP 404 from a Kubernetes
  API server while restarting a watch API request
* Linkerd Mesh Interpreter
  * Adds a failure detector in the `io.l5d.mesh` interpreter to help monitor the health of
  connections to Namerd. The failure detector can be configured by providing a `minPeriodMs`
  which sets the duration between each successive ping and a `closeTimeoutMs` parameter that
  sets a duration that must elapse before a connection is marked as "unhealthy"
* Adds support for configuring `socketOptions` in the client section of a Linkerd config

## 1.6.1 2019-02-01

The first 1.x release of the year brings minor bug fixes to Namerd, the `io.l5d.consul` and 
`io.l5d.curator` namers. This release features contributions from [NCBI](https://github.com/ncbi)
and ThreeComma. A big thank you to [edio](https://github.com/edio) and 
[Chris Goffinet](https://github.com/chrisgoffinet) for their contributions in this release.

Full release notes:

* Improves the `io.l5d.consul` namer's error handling in cases were it receives intermittent 5xx 
HTTP errors from Consul
* Fixes a `java.lang.NoSuchMethodError` that would sometimes occur when using the `io.l5d.curator` 
namer
* Fixes a `NullPointerException` that would occur when using the `io.l5d.mesh` interface in Namerd
* Adds a new configuration option called `backlog` to `socketOptions` that allows you to set up a
backlog queue size for TCP connections
* Fixes an issue where the `interpreter_state` watch endpoint would sometimes display incorrect
IP addresses

## 1.6.0 2018-12-20

Linkerd 1.6.0 includes a Finagle upgrade that reduces direct memory allocation and adds support for
more efficient HTTP/1.1 streaming for large HTTP requests. This release also improves Linkerd's 
execution script to run with Java 9 and higher. Finally, this release adds a new gRPC 
response-classifier that may be configured with user defined gRPC status codes.

Full release notes:


* **Breaking Change**
  * `requestAuthorizers` are now configured in the client section of a router configuration.
  * `maxChunkKB` has been removed and is no longer configurable for HTTP/1.1 routers. Rather than
   enforcing a hard size limit, Linkerd now streams HTTP/1.1 chunked messages that exceed 
  `streamAfterContentLengthKB`
  
HTTP/1.1
  * Adds a new config option `streamAfterContentLengthKB` that sets a threshold at which HTTP
  messages will be streamed instead of being fully buffered in memory, even when chunked-encoding is
  not used.
* Consul  
  * Fixes an issue where the last known good state of an `io.l5d.consul` namer would be cleared if 
  a 5xx API response was received from Consul.
* gRPC
  * Adds support for all `io.l5d.h2.grpc.*` response classifiers to classify gRPC status codes as 
  `Success` based off of a user defined list within the response classification section of a config.
* Fixes a startup issue where Linkerd would fail to load `readTimeoutMs` and `writeTimeoutMs`values
  from socket option configs.  
* Fixes Linkerd's executable script to work with Java version 9 and higher.
* Upgrades Finagle to 18.12.0 which reduces the amount of direct memory Linkerd allocates at startup
 time.
    
## 1.5.2 2018-11-19

Linkerd 1.5.2 adds performance improvements to HTTP/2, dramatically improving throughput when
sending many small frames as is common when using unary gRPC methods.  It also fixes a long standing
race condition where Linkerd could get stuck using out of date endpoint data from the Kubernetes
API.

Full release notes:

* HTTP/2
  * Adds buffering to the channel transport which improves throughput by up to 30% when sending many small messages.
* HTTP
  * Removes an incorrect log line about the Content-Length header when receiving a 204 response.
* Kubernetes
  * Fixes a race condition where Kubernetes endpoint sets could get stuck indefinitely with stale data.
* Prometheus
  * Moves exception names out of the metric names and into an `exception` label.
* Adds the `keepAlive` property in the server socket options config.  This allows you to enable the SO_KEEPALIVE socket option which removes dead connections that did not close properly and can therefore help prevent connection leaks. Big thanks to [Jonathan Reichhold](https://github.com/jreichhold) for this!

## 1.5.1 2018-10-24

Linkerd 1.5.1 adds a new `io.l5d.consul.interpreter` that allows Linkerd to read dtabs directly from 
a Consul KV store agent instead of using Namerd. In addition, this release fixes an issue in 
the HTTP/2 router where Linkerd would get stuck handling connections in certain cases.

This release features contributions from OfferUp, Planet Labs and Buoyant with a special shoutout
to [Leo Liang](https://github.com/leozc) and [Chris Taylor](https://github.com/ccmtaylor) for their
work on fixing a bug in the DNS SRV namer.

Full release notes:

* HTTP/2
  * Fixes an HTTP/2 issue that causes Linkerd to stop processing incoming frames on an HTTP/2
  connection after Linkerd sends a `RST_STREAM` frame to its remote peer. This was causing gRPC 
  clients to experience timeout errors intermittently because connections between Linkerd and its
  remote peers weren't being closed properly.
  * Sets the `maxConcurrentStreamsPerConnection` config value for the `h2` router to `1000` by default
  to prevent Linkerd from running out of memory when HTTP/2 clients leak connection streams.
* Consul
  * Adds a request timeout to `io.l5d.consul` namer HTTP polling requests to prevent an issue where
  the namer holds on to stale service discovery information.
  * Adds a new `io.l5d.consul.interpreter` that allows Linkerd to read dtabs directly from a Consul
  KV store.
* DNS SRV
  * Fixes an issue where the `io.l5d.dnssrv` namer would get into a bad state and fail to resolve
  service names.
* Adds support for configuring JVM GC logging in Linkerd and Namerd by default.
* Fixes a memory leak issue caused by Finagle's `BalancerRegistry` failing to properly remove
 `Balancer` objects.

## 1.5.0 2018-10-02

Linkerd 1.5.0 adds the long awaited ability to make Linkerd config changes with
zero downtime! 🤯 This release adds the `socketOptions.reusePort` config property which allows
multiple processes to bind to the same port.  In this way, you can start a new Linkerd process
and wait for it to start serving requests before gracefully shutting down the old Linkerd process.
Note that this feature is only available on Linux 3.9 distributions and newer.

This release features contributions from [Applause](https://github.com/ApplauseAQI), ThreeComma,
GuteFrage GmbH, and [Buoyant](https://github.com/buoyantio).  An extra special thank you to
[Zack Angelo](https://github.com/zackangelo) for laying the groundwork in Finagle for the reusePort
feature!

Full release notes:

* **Breaking Change**: The `threshold` and `windowSize` options have been removed from the `failureThreshold` config in the Namerd interpreter.  These options were of limited value and are no longer supported by Finagle.
* Socket Options:
  * Certain socket options may now be set on Linkerd servers by adding a `socketOptions` config in a server config.
  * Add support for the `SO_REUSEPORT` socket option.  This allows multiple processes to bind to the same port and is a great way to do zero downtime Linkerd deploys.
* Istio features are now marked as deprecated.
* Marathon:
  * Ensure traffic is not sent to Marathon services during their health-check grace period.
* Use AsyncAppender for console logging so that logging does not impact Linkerd performance.
* Upgrade to Finagle 18.9.1

## 1.4.6 2018-08-13

Linkerd 1.4.6 adds even more watch state endpoints to Linkerd's debugging arsenal, allowing you to 
inspect the state of Linkerd’s watches easily. This release adds watch state endpoints for the 
Kubernetes ConfigMap interpreter as well as the Marathon and filesystem namers.

Full release notes:

* HTTP/1.1 and HTTP/2
  * Allow HTTP/1.1 and HTTP/2 POST requests to be retryable.
  * Fix an issue where the `x-forwarded-client-cert` header was not always cleared on incoming
    requests.
* Add TLS support for the `io.l5d.etcd` namer client.
* Admin
  * Add new `io.l5d.marathon`, `io.l5d.fs`, and `io.l5d.k8s.configMap` watch state endpoints to 
  allow diagnosis of Linkerd’s various watches.
* Distributed Tracing
  * Add a new `io.l5d.zipkin` trace propagation plugin that writes Zipkin B3 trace headers to
  outgoing requests. Previously, Zipkin trace headers were ignored by Linkerd in order for Linkerd 
  to not interfere with other tracing systems like Zipkin.
* Namerd
  * Add an experimental `io.l5d.destination` interface which implements the Linkerd 
  [destination API.](https://github.com/linkerd/linkerd2-proxy-api/blob/master/proto/destination.proto)

## 1.4.5 2018-07-13

Linkerd 1.4.5 contains some minor bugfixes and introduces two much-requested features. First, it is
now possible to selectively disable Linkerd's admin endpoints, e.g., keep the UI functional but to
disable the shutdown endpoint. A huge thanks to [Robert Panzer](https://github.com/robertpanzer) for
all his hard work on this.

Second, we've added experimental support for the [OpenJ9](https://www.eclipse.org/openj9/) JVM.
Preliminary tests with OpenJ9 exhibit a 3x reduction in startup time, a 40% reduction in memory
footprint, and a 3x reduction in p99 latency. You can find a Linkerd+OpenJ9 Docker image at
`buoyantio/linkerd:1.4.5-openj9-experimental` on
[Docker Hub](https://hub.docker.com/r/buoyantio/linkerd/tags/).

Full release notes:

* Add an OpenJ9 configuration for building a Docker image with the OpenJ9 JVM
* Fix a NullPointerException when using the -validate flag
* Fix an error where diagnostic tracing did not work when receiving a chunk encoded response
* Admin
  * Add a `security` section to the admin config that controls which admin endpoints are enabled
* HTTP/2
  * Fix a memory leak when there are a large number of reset streams
  * Allow HTTP/2 response classifiers to be loaded as plugins
* Namerd
  * Fix a memory leak in the io.l5d.mesh interpreter when idle services are reaped

## 1.4.4 2018-07-06

Linkerd 1.4.4 continues our focus on diagnostics, performance, and stability. This release features 
several performance and diagnostics improvements, including better handling of HTTP/2 edge cases, 
new watch state introspection for the Consul namer, and better isolation of admin page serving from 
the primary data path. It also features a new, pluggable trace propagation module that allows for 
easier integration with tracing systems like OpenTracing.

This release features contributions from Salesforce, Walmart, WePay, Comcast, ScalaConsultants, 
OfferUp, Buoyant, and more. A big thank you to:

* [Chris Goffinet](https://github.com/chrisgoffinet)
* [Dan Vulpe](https://github.com/dvulpe)
* [Ivano Pagano](https://github.com/ivanopagano)
* [Leo Liang](https://github.com/leozc)
* [Mantas Stoškus](https://github.com/mstoskus)
* [Mohsen Rezaei](https://github.com/mrezaei00) 
* [Nick K](https://github.com/utrack)
* [Priyasmita Bagchi](https://github.com/pbagchi)
* [Robert Panzer](https://github.com/robertpanzer)
* [Ryan Michela](https://github.com/rmichela)
 
Full release notes:

* Distributed Tracing
  * Refactor Linkerd's trace propagation module to be pluggable. This allows better integration with
   tracing systems like OpenTracing and allows users to write Linkerd trace propagation plugins for 
   arbitrary tracing systems.
* TLS
  * Deprecate the  `trustCerts` config field in the client TLS section in favor of 
  `trustCertsBundle`. This allows you to use multiple trust certs in one file and avoids the need 
  for Linkerd to create temporary files.
* HTTP, HTTP/2
  * Fix an issue where Linkerd sometimes interprets HTTP/1.0 response with no Content-Length as a 
  chunked response.
  * Improve error messages by adding contextual routing information to a `ConnectionFailed` 
  exception sent back to a client via Linkerd.
  * Add a gRPC standard-compliant response classifier.
  * Fix an issue where Linkerd doesn't add an `l5d-err` header in an HTTP/2 response.
  * Fix an issue where Linkerd does not handle HTTP/2 requests with invalid HTTP status codes 
  correctly.
* Consul
  * Add new watch state instrumentation feature to the `io.l5d.consul` namer.
  * Fix an issue where the `io.l5d.consul` namer sometimes does not retry `ConnectionRefused` 
  exception.
  * Fix an issue where the `io.l5d.consul` namer returns a single IP for a service node instead of
   multiple IP addresses for a service node.
* Admin
  * Fix an issue where Linkerd may slow down data plane requests when the admin server is under 
  heavy load.
  * Improve performance of the Prometheus telemeter when serving metrics for a high cardinality 
  of services.
  * Fix an issue where the `intepreter_state` endpoint was not available for interpreters that 
  contained a transformer.
  * Fix the `namer_state` endpoint to expose namers that use transformers.
* Namerd
  * Fix an issue where null values were accepted by the Dtab HTTP API.

## 1.4.3 2018-06-12

This is a follow up release that includes diagnostic tracing for H2 requests.

Full release notes:

* Add diagnostic tracing for H2 requests, allowing Linkerd to add h2 request routing information at
the end of h2 streams to downstream services.
* Pass stack params to announcer plugins, allowing them to report metrics correctly. 

## 1.4.2 2018-06-11

Linkerd 1.4.2 continues its focus on diagnostics and stability. This release introduces Diagnostic 
Tracing, a feature that helps describe how Linkerd routes requests by displaying detailed routing 
information from each hop through your application. Stay tuned for a deep dive blog post about this 
feature coming soon. 

We’re also excited to share improvements to Linkerd’s error handling. Previously, when Linkerd 
failed to route a request, it could fail with a notoriously confusing `No Hosts Available` error. 
Now, these errors include more useful, informative diagnostic information to help explain the cause 
of the failure.

Full release notes:

* Diagnostics
   * Improve error reporting when receiving `No Hosts Available` exception.  Linkerd returns a less cryptic user-friendly message that includes information such as alternative service name resolutions and dtabs used for name resolution.
   * Add a new diagnostic tracing feature. It allows Linkerd to add routing information to the response of a `TRACE` request forwarded to a service.
* Fixes an issue where underscores in match patterns of `io.l5d.rewrite` no longer work.

## 1.4.1 2018-05-25

Linkerd 1.4.1 is focused on adding diagnostics and improved behavior in
production environments.

This release features contributions from Strava, Signal, OfferUp, Scalac,
Salesforce, and Buoyant.  A big thank you to:

* [Alex Leong](https://github.com/adleong/)
* [Dan Vulpe](https://github.com/dvulpe)
* [Dennis Adjei](https://github.com/dadjeibaah)
* [J Evans](https://github.com/jayeve)
* [Justin Venus](https://github.com/JustinVenus)
* [Leo Liang](https://github.com/leozc)
* [Matthew Huxtable](https://github.com/mhuxtable)
* [Michał Mrożek](https://github.com/mmrozek)
* [Peter Fich](https://github.com/peterfich)
* [Robert Panzer](https://github.com/robertpanzer)
* [Shakti Das](https://github.com/shakti-das)

Full release notes:

* Diagnostics:
  * Add watch state admin endpoints where you can inspect the current state of Linkerd's watches, including information such as time of last update and last known value.  These can be extremely valuable for debugging communication between Linkerd and other components such as Namerd or Kubernetes.
    * Kubernetes namer watch state: `/namer_state/io.l5d.k8s.json`
    * Namerd interpreter watch state: `/interpreter_state/io.l5d.namerd/<namespace>.json`
    * Namerd mesh interpreter watch state: `/interpreter_state/io.l5d.mesh/<root>.json`
* TLS:
  * Add the `intermediateCertsPath` config setting to client and server TLS.  This allows you to specify a file containing intermediate CA certificates supporting the main certificate.
  * Allow the TLS protocols to be configured which enables the ability to use TLSv1.2 specific ciphers.
* HTTP, HTTP/2:
  * Avoiding upgrading HTTP/1.0 requests to HTTP/1.1.  This prevents servers from sending responses that the client cannot handle (e.g. chunk encoded responses).
  * Fix a bug where Linkerd was not writing the `l5d-ctx-*` headers on HTTP/2 requests..
  * Add support for adding a 'Forwarded' header to HTTP/2 requests.
* Make Linkerd and Namerd honor the shutdown grace period when using the `/admin/shutdown` endpoint.
* Pass stack params to announcer plugins, allowing them to report metrics correctly.
* Add support for extracting substrings in path patterns.  This allows you to, for example, configure TLS commonNames based on substrings of a path segment instead of the entire segment.
* Add a TTL to Namerd's inactive cache so that Namerd will tear down watches on idle services.
* Improve fallback behavior in the io.l5d.marathon namer so that fallback occurs if an app has no replicas.
* Fix an ArrayIndexOutOfBoundsException in ForwardClientCertFilter.

## 1.4.0 2018-04-30

Linkerd 1.4.0 upgrades us to the latest versions of Finagle and Netty and
features lower memory usage for large payloads. Two new configuration options
have been introduced: client connection lifetimes and access log rotation
policy. One breaking change has been introduced around the configuration file
syntax for loggers.  This release features contributions from ThreeComma,
[ScalaConsultants](https://github.com/ScalaConsultants), [Salesforce](https://github.com/salesforce), and [Buoyant](https://github.com/buoyantio).

* **Breaking Change**: Rename the loggers section of the Linkerd config to requestAuthorizers to match the name of the plugin type ([#1900](https://github.com/linkerd/linkerd/pull/1900))
* Tune Netty/Finagle settings to reduce direct memory usage ([#1889](https://github.com/linkerd/linkerd/pull/1889)). This should dramatically reduce direct memory usage when transferring large payloads.
* Introduce a ClientSession configuration section that provides ways to control client connection lifetime ([#1903](https://github.com/linkerd/linkerd/pull/1903)).
* Expose rotation policy configuration for http and http2 access logs ([#1893](https://github.com/linkerd/linkerd/pull/1893)).
* Stop logging harmless reader discarded errors in k8s namer ([#1901](https://github.com/linkerd/linkerd/pull/1901)).
* Disable autoloading of the default tracer in Namerd ([#1902](https://github.com/linkerd/linkerd/pull/1902)). This prevents Namerd from attempting to connect to a Zipkin collector that doesn't exist.
* Upgrade to Finagle 18.4.0.

## 1.3.7 2018-04-5

Linkerd 1.3.7 includes memory leak fixes, tons of improvements for Consul, and more!  This release features contributions from ThreeComma, [NCBI](https://github.com/ncbi), WePay, [Salesforce](https://github.com/salesforce), [Homeaway](https://github.com/homeaway), Prosoft, and [Buoyant](https://github.com/buoyantio).

* Add support for more types of client certificates in the ForwardClientCertFilter ([#1850](https://github.com/linkerd/linkerd/pull/1850)).
* Improve documentation on how to override the base Docker image ([#1867](https://github.com/linkerd/linkerd/pull/1867)).
* Improve the efficientcy of the dtab delegator UI ([#1862](https://github.com/linkerd/linkerd/pull/1862)).
* Add a command line flag for config file validation ([#1854](https://github.com/linkerd/linkerd/pull/1854)).
* Fix a bug where the wrong timezone was being used in access logs ([#1851](https://github.com/linkerd/linkerd/pull/1851)).
* Add the ability to explicitly disable TLS for specific clients ([#1856](https://github.com/linkerd/linkerd/pull/1856)).
* Consul:
  * Add support for TLS-encrypted communication with Consul ([#1842](https://github.com/linkerd/linkerd/pull/1842)).
  * Fix a connection leak to Consul ([#1877](https://github.com/linkerd/linkerd/pull/1877)).
  * Fix a bug where Linkerd would timeout requests to non-existent Consul datacenters ([#1863](https://github.com/linkerd/linkerd/pull/1877)).
* Remove many alarming but harmless error messages from Linkerd and Namerd logs ([#1871](https://github.com/linkerd/linkerd/pull/1871), [#1884](https://github.com/linkerd/linkerd/pull/1884), [#1875](https://github.com/linkerd/linkerd/pull/1875)).
* Fix ByteBuffer memory leaks in HTTP/2 ([#1879](https://github.com/linkerd/linkerd/pull/1879), [#1858](https://github.com/linkerd/linkerd/pull/1858)).

## 1.3.6 2018-03-01

This release focuses on correctness and bug fixes. Much of the work was in service to Linkerd's Kubernetes and Consul support. This release features contributions from [Salesforce](https://github.com/salesforce), [NCBI](https://github.com/ncbi), [Planet Labs](https://github.com/planetlabs), [Buoyant](https://github.com/buoyantio), [FOODit](https://github.com/foodit), and Variomedia.

* Add support for DNS SAN names in XFCC ([#1826](https://github.com/linkerd/linkerd/pull/1826)). Thanks to [@shakti-das](https://github.com/shakti-das)!
* Fix ZooKeeper connection loss by better handling of session expiration ([#1830](https://github.com/linkerd/linkerd/pull/1830)).
* Fix race condition in ExistentialStability, used by Kubernetes and Rancher namers ([#1828](https://github.com/linkerd/linkerd/pull/1828)).
* Fix inotify leaks in `io.l5d.fs` by cleaning them up in case of errors ([#1787](https://github.com/linkerd/linkerd/pull/1787)).
* Fix access logs writing to same file when configured via multiple routers ([#1837](https://github.com/linkerd/linkerd/pull/1837)).
* Kubernetes
  * Fix ingress cache resetting when it should not ([#1817](https://github.com/linkerd/linkerd/pull/1817)). Thanks to [@negz](https://github.com/negz)!
  * Introduce a `ignoreDefaultBackends` config key under `io.l5d.ingress`. This adds a 'strict' Kubernetes ingress identifier that ignores default backends ([#1794](https://github.com/linkerd/linkerd/pull/1794)). Thanks to [@negz](https://github.com/negz)!
  * Fix issue where Kubernetes ingresses are sometimes not deleted ([#1810](https://github.com/linkerd/linkerd/pull/1810)).
  * Fix namer client stats by using a single client for the entire `io.l5d.k8s` namer ([#1774](https://github.com/linkerd/linkerd/pull/1774)).
  * Log unexpected responses from the Kubernetes API ([#1790](https://github.com/linkerd/linkerd/pull/1790)).
* Consul
  * Fix namerd admin interface hanging ([#1816](https://github.com/linkerd/linkerd/pull/1816)). Thanks to [@Ashald](https://github.com/Ashald), and also [@hynek](https://github.com/hynek) for testing!
  * Do not rely on ConsulApi retries in `io.l5d.consul` namer  ([#1827](https://github.com/linkerd/linkerd/pull/1827)). Thanks to [@edio](https://github.com/edio).
* TLS
  * Add test case to ensure `x-forwarded-client-cert` header can't be spoofed ([#1811](https://github.com/linkerd/linkerd/pull/1811)). Thanks to [@drichelson](https://github.com/drichelson)!
* Namerd
  * Add ability to record stats from `DtabStore` plugins ([#1801](https://github.com/linkerd/linkerd/pull/1801)). Thanks to [@edio](https://github.com/edio)!
* Admin
  * Use the system configured timezone when formatting logs ([#1833](https://github.com/linkerd/linkerd/pull/1833)). Thanks to [@fantayeneh](https://github.com/fantayeneh)!
* HTTP/2
  * Add access logging, via `h2AccessLog` config key in `h2` routers ([#1786](https://github.com/linkerd/linkerd/pull/1786)).

## 1.3.5 2018-01-17

This release focuses on quality, and on improving the debugging process. It includes improvements and fixes for Linkerd's Kubernetes support, administrative UI, and Namerd control plane. It officially graduates HTTP/2 support out of experimental, and also features a number of community contributions!

* 🎓 H2 router and `io.l5d.mesh` Namerd interface are no longer experimental ([#1782](https://github.com/linkerd/linkerd/pull/1782))! 🎓
* Add an experimental namer for Rancher service discovery ([#1740](https://github.com/linkerd/linkerd/pull/1740)). A huge thank you to [@fangel](https://github.com/fangel) for contributing this namer!
* Kubernetes
  * Fix a bug that could cause the `io.l5d.k8s` namer to get "stuck" and fail to receive updates from an endpoint ([#1755](https://github.com/linkerd/linkerd/pull/1755)). Contributed by [@obeattie](https://github.com/obeattie).
* Admin UI
  * Add a /client_state.json debugging endpoint to expose the current address set of each client, allowing you to easily inspect where Linkerd thinks it can send traffic to ([#1768](https://github.com/linkerd/linkerd/pull/1768)).
  * Fix an error when using the admin UI to perform delegations with a dtab stored in Namerd over the `io.l5d.thriftNameInterpreter` interface ([#1762](https://github.com/linkerd/linkerd/pull/1762)). Thanks to [@jackkleeman](https://github.com/jackkleeman)!
  * Render an error instead of a blank page for failures on Namerd's dtab playground ([#1770](https://github.com/linkerd/linkerd/pull/1770)).
* Namerd
  * Errors parsing dtabs stored in Consul are now surfaced in log messages ([#1760](https://github.com/linkerd/linkerd/pull/1760)).
  * Fix an error where Linkerd could sometimes miss updates from Namerd when using the `io.l5d.thriftNameInterpreter` interface ([#1753](https://github.com/linkerd/linkerd/pull/1753)). Thanks to [@obeattie](https://github.com/obeattie)!

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
* We now have a [slack channel](https://slack.linkerd.io)! Operators are
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
  [Finagle](https://twitter.github.io/finagle/) library.
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
