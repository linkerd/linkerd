resolvers += "twitter-repo" at "https://maven.twttr.com"

// formatting
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")

// testing
addSbtPlugin("org.scoverage"     % "sbt-scoverage" % "1.3.3")

// doc generation
addSbtPlugin("com.eed3si9n"      % "sbt-unidoc"    % "0.3.3")

// packaging
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"  % "0.14.1")
addSbtPlugin("se.marcuslonnberg" % "sbt-docker"    % "1.2.0")

// scrooge
addSbtPlugin("com.twitter" %% "scrooge-sbt-plugin" % "4.6.0")

// microbenchmarking for tests.
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.6")
