# Telemetry

A telemeter may receive stats and trace annotations, i.e. to send to a collector
or export. Telemetry data can be collected and exported from a linkerd process by
configuring telemeters via a top-level `telemetry` section.

<aside class="notice"> These parameters are available to the telemeter
regardless of kind. Telemeters may also have kind-specific parameters. </aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.prometheus`](#prometheus), [`io.l5d.statsd`](#statsd-experimental), [`io.l5d.tracelog`](#tracelog), [`io.l5d.recentRequests`](#recent-requests), or [`io.l5d.zipkin`](#zipkin-telemeter).
experimental | `false` | Set this to `true` to enable the telemeter if it is experimental.

## Prometheus

> Example Prometheus config

```yaml
telemetry:
- kind: io.l5d.prometheus
```

kind: `io.l5d.prometheus`

Exposes admin endpoints:

* `/admin/metrics/prometheus`: retrieve all metrics in [Prometheus](https://prometheus.io) format

This telemeter has no additional parameters.

## InfluxDB

> Example InfluxDB config

```yaml
telemetry:
- kind: io.l5d.influxdb
```

kind: `io.l5d.influxdb`

This telemeter is intended for collection by
[Telegraf](https://github.com/influxdata/telegraf). Each measurement will have a
`host` tag, set from the `Host` header on the collector's incoming request.
Recommended Telegraf configuration is using `inputs.exec` plugin with
`curl -s http://[LINKERD_IP]/admin/metrics/influxdb`.

Exposes admin endpoints:

* `/admin/metrics/influxdb`: retrieve all metrics in [InfluxDB](https://influxdata.com) LINE protocol format

This telemeter has no additional parameters.

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

## Zipkin telemeter

> Example zipkin config

```yaml
telemetry:
- kind: io.l5d.zipkin
  host: localhost
  port: 9410
  sampleRate: 0.02
```

kind: `io.l5d.zipkin`

Finagle's [zipkin-tracer](https://github.com/twitter/finagle/tree/develop/finagle-zipkin).
Use this telemeter to send trace data to a Zipkin Scribe collector.

Key | Default Value | Description
--- | ------------- | -----------
host | `localhost` | Host to send trace data to.
port | `9410` | Port to send trace data to.
sampleRate | `0.001` | What percentage of requests to trace.
