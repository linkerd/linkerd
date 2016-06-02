# Tracers

*(for the [tracers](config.md#tracers) key)*

Requests that are routed by linkerd are also traceable using Finagle's built-in
tracing instrumentation.  A tracer config object has the following parameters:

* *kind* -- The name of the tracer plugin
* *debugTrace* -- Print all traces to the console. Note this overrides the
global `-com.twitter.finagle.tracing.debugTrace` flag, and will default to
that flag if not set here.
* Any options specific to the tracer

Current tracers include:

## Zipkin

`io.l5d.zipkin`

Finagle's [zipkin-tracer](https://github.com/twitter/finagle/tree/develop/finagle-zipkin).

* *host* -- Optional. Host to send trace data to. (default: localhost)
* *port* -- Optional. Port to send trace data to. (default: 9410)
* *sampleRate* -- Optional. How much data to collect. (default: 0.001)

For example:

```yaml
tracers:
- kind: io.l5d.zipkin
  host: localhost
  port: 9410
  sampleRate: 0.02
  debugTrace: true
```
