val zioVersion     = "1.0.4"
val monocleVersion = "3.0.0-M4"
val sttpVersion    = "3.1.7"

lazy val torrenties = (project in file("."))
  .settings(
    name := "torrenties",
    version := "0.1.0",
    scalaVersion := "2.13.4",
    scalacOptions ++= Seq("-Wconf:cat=unused:info", "-Ymacro-annotations"),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "io.github.arainko"             %% "bencode"                       % "0.1.0",
      "io.scalaland"                  %% "chimney"                       % "0.6.1",
      "org.scodec"                    %% "scodec-core"                   % "1.11.7",
      "io.github.kitlangton"          %% "zio-magic"                     % "0.2.3",
      "dev.zio"                       %% "zio-nio"                       % "1.0.0-RC10",
      "dev.zio"                       %% "zio-logging"                   % "0.5.8",
      "com.github.julien-truffaut"    %% "monocle-core"                  % monocleVersion,
      "com.github.julien-truffaut"    %% "monocle-macro"                 % monocleVersion,
      "com.softwaremill.sttp.client3" %% "core"                          % sttpVersion,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpVersion,
      "dev.zio"                       %% "zio-config"                    % zioVersion,
      "dev.zio"                       %% "zio-config-typesafe"           % zioVersion,
      "dev.zio"                       %% "zio"                           % zioVersion,
      "dev.zio"                       %% "zio-test"                      % zioVersion % "test",
      "dev.zio"                       %% "zio-test-sbt"                  % zioVersion % "test",
      "dev.zio"                       %% "zio-test-magnolia"             % zioVersion % "test"
    )
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
Global / scalacOptions += "-Ymacro-annotations"

ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"
