FROM java AS compile
MAINTAINER wjwinner@139.com
WORKDIR /code
COPY . /code
RUN ./sbt linkerd/assembly 

FROM buoyantio/linkerd:1.1.3 AS release
COPY --from=compile /code/linkerd/target/scala-2.12/linkerd-1.2.0-rc2-SNAPSHOT-exec /io.buoyant/linkerd/1.1.3/bundle-exec
