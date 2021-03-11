val zioVersion = "1.0.4"

lazy val torrenties = (project in file("."))
  .settings(
    name := "torrenties",
    version := "1.0.0",
    scalaVersion := "2.13.4",
    scalacOptions ++= Seq("-Wconf:cat=unused:info", "-Ymacro-annotations"),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "io.github.arainko" %% "bencode"           % "0.0.5",
      "dev.zio"           %% "zio-nio"           % "1.0.0-RC10",
      "dev.zio"           %% "zio"               % zioVersion,
      "dev.zio"           %% "zio-test"          % zioVersion % "test",
      "dev.zio"           %% "zio-test-sbt"      % zioVersion % "test",
      "dev.zio"           %% "zio-test-magnolia" % zioVersion % "test"
    )
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

scalafixScalaBinaryVersion in ThisBuild := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"
