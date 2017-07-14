resolvers += "twitter-repo" at "https://maven.twttr.com"
ivyScala := ivyScala.value.map(_.copy(overrideScalaVersion = true))

// formatting
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")

// testing
addSbtPlugin("org.scoverage"  % "sbt-scoverage" % "1.5.0")

addSbtPlugin("org.scoverage"  % "sbt-coveralls" % "1.1.0")

// doc generation
addSbtPlugin("com.eed3si9n"   % "sbt-unidoc"    % "0.3.3")

// packaging
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"  % "0.14.1")

addSbtPlugin("se.marcuslonnberg" % "sbt-docker"    % "1.4.1")

// scrooge
addSbtPlugin("com.twitter" %% "scrooge-sbt-plugin" % "4.18.0")

// microbenchmarking for tests.
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.6")

// pgp signing for publishing to sonatype
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")

// our grpc building extends the wrapped sbt-protobuf
addSbtPlugin("com.github.gseitz" % "sbt-protobuf" % "0.5.3")
