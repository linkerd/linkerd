# Tracers

Requests that are routed by linkerd are also traceable using Finagle's built-in
tracing instrumentation.

<aside class="notice">
These parameters are available to the tracer regardless of kind. Tracers may also have kind-specific parameters.
</aside>

Key | Default Value | Value Description
--- | ------------- | -----------------
kind | _required_ | `io.l5d.zipkin`
debugTrace | `false` | If `true`, print all traces to the console. Note this overrides the global `-com.twitter.finagle.tracing.debugTrace` flag, and will default to that flag if not set here.


## Zipkin

> Example zipkin config

```yaml
tracers:
- kind: io.l5d.zipkin
  host: localhost
  port: 9410
  sampleRate: 0.02
  debugTrace: true
```

kind: `io.l5d.zipkin`

Finagle's [zipkin-tracer](https://github.com/twitter/finagle/tree/develop/finagle-zipkin).

Key | Default Value | Value Description
--- | ------------- | -----------------
host | `localhost` | Host to send trace data to.
port | `9410` | Port to send trace data to.
sampleRate | `0.001` | What percentage of requests to trace.

