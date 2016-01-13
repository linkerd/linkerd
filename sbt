#!/bin/bash

set -e

sbtver=0.13.9
sbtjar=.sbt-launch.jar
sbtsha128=1de48c2c412fffc4336e4d7dee224927a96d5abc
sbtrepo=https://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch

validjar() {
  checksum=`openssl dgst -sha1 $sbtjar | awk '{ print $2 }'`
  [ "$checksum" = $sbtsha128 ]
}

if [ -f $sbtjar ] && ! validjar ; then
    echo "bad $sbtjar" >&2
    mv $sbtjar "${sbtjar}.invalid"
fi

if [ ! -f $sbtjar ]; then
  echo "downloading $sbtjar" >&2
  curl -L --silent --fail -o $sbtjar $sbtrepo/$sbtver/sbt-launch.jar
fi

if ! validjar ; then
    echo "bad $sbtjar.  delete $sbtjar and run $0 again." >&2
    exit 1
fi

[ -f ~/.sbtconfig ] && . ~/.sbtconfig

java -ea                          \
  $SBT_OPTS                       \
  $JAVA_OPTS                      \
  -Djava.net.preferIPv4Stack=true \
  -XX:+AggressiveOpts             \
  -XX:+UseParNewGC                \
  -XX:+UseConcMarkSweepGC         \
  -XX:+CMSParallelRemarkEnabled   \
  -XX:+CMSClassUnloadingEnabled   \
  -XX:ReservedCodeCacheSize=128m  \
  -XX:SurvivorRatio=128           \
  -XX:MaxTenuringThreshold=0      \
  -Xss8M                          \
  -Xms512M                        \
  -Xmx2G                          \
  -server                         \
  -jar $sbtjar "$@"
