# Running in Docker #

Soon, we'll publish docker images to a public repository.  In the
meantime, a docker image may be built as follows:

```sh
LINKERD_VERSION=0.0.11
git clone -b release-0.0.11 git@github.com:BuoyantIO/linkerd.git
./sbt linkerd/bundle:docker
```

This produces a docker image, `io.buoyant/linkerd:0.0.11`, with the
`L5D_HOME` and `L5D_EXEC` environment variables set to indicate
indicate linkerd's home directory and executable.  The default
entrypoint is set to `L5D_EXEC` so it may be invoked like:

```
docker --net=host --expose=4140 -P -v path/to/config.yml:config.yml io.buoyant/linkerd:0.0.11 config.yml
```

**Note**: you must expose the relevant server ports for each configuration.

## Providing additional plugins ##

linkerd can load plugins, in the form of additional JAR files, at
startup time.  These JARs must be placed in `$L5D_HOME/plugins`

For example:

```
FROM io.buoyant/linkerd:0.0.11

COPY myplugin.jar $L5D_HOME/plugins/myplugin.jar
```
