FROM williamyeh/scala:latest
MAINTAINER wjwinner@139.com
WORKDIR /code
COPY . /code
RUN apt update && apt install -y git
RUN ./sbt linkerd/assembly 
