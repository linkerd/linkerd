# Announcers

An announcer registers servers in service discovery.  Each server may specify
a list of concrete names to announce as in the [announce](#announce)
server key.  Each announcer has a prefix and will only announce names that
begin with that prefix.


<aside class="notice">
These parameters are available to the announcer regardless of kind. Announcers may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Only [`io.l5d.serversets`](#serversets) is available at this time.
prefix | kind-specific | Announces names beginning with `/#/<prefix>`.

## Serversets

kind: `io.l5d.serversets`

Announce to ZooKeeper using the serverset format.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.serversets` | Announces names beginning with `/#/<prefix>`.
zkAddrs | _required_ | A list of ZooKeeper addresses, each of which have `host` and `port` parameters.
pathPrefix | `/discovery` | The ZooKeeper path under which services should be registered.
