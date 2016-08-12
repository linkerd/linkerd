# Interpreter

*(for the [interpreter](config.md#interpreter) key)*

An interpreter determines how names are resolved.  An interpreter config block
must contain a `kind` parameter which indicates which interpreter plugin to use.

### Example

```yaml
routers:
- ...
  interpreter:
    kind: io.l5d.namerd
    dst: /$/inet/1.2.3.4/4180
```

## Default

The default interpreter resolves names via the configured
[`namers`](config.md#namers), with a fallback to the default Finagle
`Namer.Global` that handles paths of the form `/$/`.

## namerd

`io.l5d.namerd`

The namerd interpreter offloads the responsibilities of name resolution to the
namerd service.  Any namers configured in this linkerd are not used.  This
interpreter accepts the following parameters:

* *dst* -- Required.  A finagle path locating the namerd service.
* *namespace* -- Optional.  This indicates which namerd dtab to use.
  (default: default)
*retry* -- Optional.  An object configuring retry backoffs for requests to
 namerd.  (default: (5 seconds, 10 minutes))
  * *baseSeconds* -- The base number of seconds to wait before retrying.
  * *maxSeconds* -- The maximum number of seconds to wait before retrying.

## File-System

`io.l5d.fs`

The file-system interpreter resolves names via the configured
[`namers`](config.md#namers), just like the default interpreter, but also uses
a dtab read from a file on the local file-system.  The specified file is watched
for changes so that the dtab may be edited live.  This interpreter accepts the
following parameters:

* *dtabFile* -- Required.  The file-system path to a file containing a dtab.
