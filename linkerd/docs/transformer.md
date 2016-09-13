# Transformer

> Example Transformer Configuration

```yaml
routers:
- ...
  interpreter:
    ...
    transformers:
    - kind: io.l5d.localhost
```

Transformers perform a transformation on the addresses resolved by the
interpreter.  Transformations are applied sequentially in the order they appear.


Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | One of the transformer kinds listed below.

## Localhost

kind: `io.l5d.localhost`

The localhost transformer filters the list of addresses down to only addresses
that have the same IP address as localhost.  The IP of localhost is determined
by doing a one-time dns lookup of the local hostname.  This transformer can be
used by an incoming router to only route traffic to local destinations.

## Port

kind: `io.l5d.port`

The port transformer replaces the port number in every addresses with a
configured value.  This can be used if there is an incoming linkerd router (or
other reverse-proxy) running on a fixed port on each host and you with to send
traffic to that port instead of directly to the destination address.

Key | Default Value | Description
--- | ------------- | -----------
port | _required_ | The port number to use.
