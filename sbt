#!/bin/bash

set -e

sbtver=1.2.8
sbtjar=.sbt-launch.jar
sbtsha128=073c169c6e1a47b8ae78a7a718b907424dedab30
sbtrepo=https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch

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
