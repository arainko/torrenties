val zioVersion = "1.0.4"

lazy val torrenties = (project in file("."))
  .settings(
    name := "torrenties",
    version := "0.0.1",
    scalaVersion := "2.13.4",
    scalacOptions ++= Seq("-Wconf:cat=unused:info", "-Ymacro-annotations"),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "io.github.arainko"             %% "bencode"           % "0.0.5",
      "org.scodec"                    %% "scodec-core"       % "1.11.7",
      "io.circe"                      %% "circe-core"        % "0.14.0-M4",
      "com.softwaremill.sttp.client3" %% "core"              % "3.1.7",
      "io.github.kitlangton"          %% "zio-magic"         % "0.1.11",
      "dev.zio"                       %% "zio-nio"           % "1.0.0-RC10",
      "dev.zio"                       %% "zio"               % zioVersion,
      "dev.zio"                       %% "zio-test"          % zioVersion % "test",
      "dev.zio"                       %% "zio-test-sbt"      % zioVersion % "test",
      "dev.zio"                       %% "zio-test-magnolia" % zioVersion % "test"
    )
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

scalafixScalaBinaryVersion in ThisBuild := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"
