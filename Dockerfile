FROM buoyantio/linkerd:1.2.0-rc2
MAINTAINER wjwinner@139.com
WORKDIR /code
COPY . /code
RUN apt update && apt install -y git
RUN ./sbt linkerd/assembly 
