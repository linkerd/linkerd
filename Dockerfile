FROM java
MAINTAINER wjwinner@139.com
WORKDIR /code
COPY . /code
RUN ./sbt linkerd/assembly 
