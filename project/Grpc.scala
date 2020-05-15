import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt._
import sbt.Keys._
import sbtassembly.AssemblyKeys._
import sbtdocker.DockerKeys._
import sbtprotobuf.ProtobufPlugin._
import scala.language.implicitConversions
import scoverage.ScoverageKeys._

object Grpc extends Base {

  val execScript =
    """|#!/bin/sh
       |exec "${JAVA_HOME:-/usr}/bin/java" ${JVM_OPTIONS:-$DEFAULT_JVM_OPTIONS} \
       |     -cp "$0" -server io.buoyant.grpc.gen.Main "$@"
       |""".stripMargin

  val gen = projectDir("grpc/gen")
    .settings(
      mainClass := Some("io.buoyant.grpc.gen.Main"),
      mainClass in assembly := Some("io.buoyant.grpc.gen.Main"),
      assemblyJarName in assembly := s"protoc-gen-io.buoyant.grpc",
      assemblyOption in assembly := (assemblyOption in assembly).value.
        copy(prependShellScript = Some(execScript.split("\n").toSeq)),
      coverageEnabled := false)
    .withLibs(Deps.protobuf)
    .withTwitterLib(Deps.twitterUtil("app"))


  val runtime = projectDir("grpc/runtime")
    .dependsOn(Finagle.h2)
    .withLibs(Deps.protobuf)
    .withTests

  /*
   * Settings for generated modules.
   */

  private[this] def EnvPath = sys.env.getOrElse("PATH", "/bin:/usr/bin:/usr/local/bin")

  val grpcGenExec =
    taskKey[Option[File]]("Location of the protoc-io.buoyant.grpc plugin binary")

  // By default, assemble the plugin from the gen project. The
  // plugin is then linked into a temporary directory so that it
  // may be safely added to the PATH without exposing unintended
  // executables.
  private[this] def grpcGenExec0 =
    (assembly in gen, target).map[Option[File]] { (exec, targetDir) =>
      val link = IO.createUniqueDirectory(targetDir) / "protoc-gen-io.buoyant.grpc"
      IO.copyFile(exec, link)
      link.setExecutable(true)
      Some(link)
    }

  // Ensure that the protoc-gen-io.buoyant.grpc plugin has been
  // assembled and is on the PATH when protoc runs.
  private[this] def runProtoc0 =
    (streams, protoc, grpcGenExec).map { (streams, protoc, genExec) =>
      val log = streams.log
      val env = genExec match {
        case None => Map.empty[String, String]
        case Some(ge) => Map("PATH" -> s"${ge.getCanonicalFile.getParent}:${EnvPath}")
      }
      (args: Seq[String]) => {
        val cmd = protoc +: args
        env.foreach { case (k, v) => log.debug(s":; export ${k}=${v}") }
        log.debug(":; " + cmd.mkString(" "))
        Process(cmd, None, env.toSeq:_*) ! log
      }
    }

  /** sbt-protobuf, without protobuf-java */
  val grpcGenSettings =
    protobufSettings ++ inConfig(protobufConfig)(Seq(
      javaSource := (javaSource in Compile).value,
      scalaSource := (sourceManaged in Compile) { _ / "compiled_protobuf" }.value,
      generatedTargets := scalaSource { d => Seq(d -> "*.pb.scala") }.value,
      protoc := "./protoc",
      grpcGenExec := grpcGenExec0.value,
      runProtoc := runProtoc0.value,
      protocOptions := scalaSource { ss =>
        Seq(s"--io.buoyant.grpc_out=plugins=grpc:${ss.getCanonicalPath}")
      }.value
    )) ++ Seq(
      // Sbt has trouble if scalariform rewrites the generated code
      excludeFilter in ScalariformKeys.format := "*.pb.scala",
      coverageExcludedFiles := """.*\.pb\.scala""",

      // We don't need protobuf-java.
      libraryDependencies := libraryDependencies(_.filterNot(_.name == "protobuf-java")).value
    )

  case class GrpcProject(project: Project) {
    def withGrpc: Project = project.settings(grpcGenSettings).dependsOn(runtime)
  }
  implicit def withGrpcProject(p: Project): GrpcProject = GrpcProject(p)

  /** Example */
  val eg = projectDir("grpc/eg")
    .withGrpc
    .withTests()
    .settings(publishArtifact := false)

  val interop = projectDir("grpc/interop")
    .withGrpc
    .withTests()
    .withTwitterLib(Deps.twitterServer)
    .settings(appAssemblySettings)
    .settings(publishArtifact := false)

  val all = aggregateDir("grpc", eg, gen, interop, runtime)
}
