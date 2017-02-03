# Telemetry

A telemeter may receive stats and trace annotations, i.e. to send to a collector
or export. Telemetry data can be collected and exported from a linkerd process by
configuring telemeters via a top-level `telemetry` section.

<aside class="notice"> These parameters are available to the telemeter
regardless of kind. Telemeters may also have kind-specific parameters. </aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.commonMetrics`](#commonmetrics), [`io.l5d.statsd`](#statsd-experimental), [`io.l5d.tracelog`](#tracelog), or [`io.l5d.recentRequests`](#recent-requests).
experimental | `false` | Set this to `true` to enable the telemeter if it is experimental.

## CommonMetrics

> Example CommonMetrics config

```yaml
telemetry:
- kind: io.l5d.commonMetrics
```

kind: `io.l5d.commonMetrics`

Exposes admin endpoints:

* `/admin/metrics`: retrieve a given set of metrics in [twitter-server](https://twitter.github.io/twitter-server/) format
* `/admin/metrics.json`: retrieve all metrics in [twitter-server](https://twitter.github.io/twitter-server/) format
* `/admin/metrics/prometheus`: retrieve all metrics in [Prometheus](https://prometheus.io/) format

This telemeter has no additional parameters.

## Admin Metrics Export

> Example Admin Metrics Export config

```yaml
telemetry:
- kind: io.l5d.adminMetricsExport
  snapshotIntervalSecs: 60
```

kind: `io.l5d.adminMetricsExport`

Exposes admin endpoint:

* `/admin/metrics.json`: retrieve all metrics in [twitter-server](https://twitter.github.io/twitter-server/) format

This is a replacement for the `io.l5d.commonMetrics` telemeter which will be deprecated in the
future.

## StatsD (experimental)

> Example StatD config

```yaml
telemetry:
- kind: io.l5d.statsd
  experimental: true
  prefix: linkerd
  hostname: 127.0.0.1
  port: 8125
  gaugeIntervalMs: 10000
  sampleRate: 1.0
```

kind: `io.l5d.statsd`

[StatsD](https://github.com/etsy/statsd) metrics exporting. This telemeter
connects to a given StatsD server via UDP. Counters and timers/histograms are
exported immediately, based on sample rate. Gauge export interval is
configurable.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because this telemeter is still considered experimental, you must set this to `true` to use it.
prefix | `linkerd` | String to prefix all exported metric names with.
hostname | `127.0.0.1` | Hostname of the StatsD server.
port | `8125` | Port of the StatsD server.
gaugeIntervalMs | `10000` | Interval to export Gauges, in milliseconds.
sampleRate | `0.01` | Sample rate to export counter and timing/histogram events. Higher values will result in higher linkerd latency.

## TraceLog

> Example TraceLog config

```yaml
telemetry:
- kind: io.l5d.tracelog
  sampleRate: 0.2
  level: TRACE
```

kind: `io.l5d.tracelog`

Log all tracing data, given a log-level and sample rate.

Key | Default Value | Description
--- | ------------- | -----------
host | `localhost` | Host to send trace data to.
sampleRate | `1.0` | What percentage of traces to log.
level | `INFO` | Log-level, one of: `ALL`, `CRITICAL`, `DEBUG`, `ERROR`, `FATAL`, `INFO`, `OFF`, `TRACE`, `WARNING`. For full details, see [com.twitter.logging.Level](http://twitter.github.io/util/docs/#com.twitter.logging.Level).

## Recent Requests

> Example Recent Requests config

```yaml
telemetry:
- kind: io.l5d.recentRequests
  sampleRate: 1.0
  capacity: 10
```

kind: `io.l5d.recentRequests`

The recent requests telemeter keeps an in-memory record of recent requests and uses it to populate
the recent requests table on the admin dashboard.  This table can be viewed at `/requests` on the
admin port.  Recording requests can have an impact on linkerd performance so make sure to set a
sample rate that is appropriate for your level of traffic.

Key        | Default Value | Description
---------- | ------------- | -----------
sampleRate | _required_    | What percentage of traces to record.
capacity   | 10            | The maximum number of recent traces to store

## Usage

> Example usage config

```yaml
telemetry:
 - kind: io.l5d.commonMetrics
 - kind: io.l5d.usage
```

kind `io.l5d.usage`

In order to make improvements and prioritize features, we'd like to gain a
broader picture of how users run linkerd. This opt-in telemeter helps us by
gathering anonymized usage data, sent to buoyant once an hour.

<aside class="warning"> No data is sent until the telemeter is explicitly added to the config! </aside>


The kind of data captured is as follows:

1. How linkerds are configured (The kinds of namers, initializers, identifiers, transformers, protocols, & interpreters used)
2. What environments are linkerds running in (Operating System, Container orchestration solution),
3. How do linkerds perform in those contexts (JVM performance, Number of requests served)

We do not collect the labels of namers/routers, designated service addresses or
directories, dtabs, request/response data, or any other possibly identifying information.

To see the exact payload that would be sent, add the telemeter with `dryRun` set to `true`,
then query `localhost:9990/admin/metrics/usage`

Key | Default Value | Description
--- | ------------- | -----------
orgId | empty by default | Optional string of your choosing that identifies your organization
dryRun | false | If set to true, the usage telemeter is loaded but no data is sent
