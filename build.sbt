val zioVersion = "1.0.4"
val fs2Version = "2.5.3"

lazy val torrenties = (project in file("."))
  .settings(
    name := "torrenties",
    version := "0.0.1",
    scalaVersion := "2.13.4",
    scalacOptions ++= Seq("-Wconf:cat=unused:info", "-Ymacro-annotations"),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "io.github.arainko"             %% "bencode"                       % "0.1.0",
      "com.github.julien-truffaut"    %% "monocle-core"                  % "3.0.0-M4",
      "com.github.julien-truffaut"    %% "monocle-macro"                 % "3.0.0-M4",
      "io.scalaland"                  %% "chimney"                       % "0.6.1",
      "org.scodec"                    %% "scodec-core"                   % "1.11.7",
      "io.circe"                      %% "circe-core"                    % "0.14.0-M4",
      "com.softwaremill.sttp.client3" %% "core"                          % "3.1.7",
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.1.7",
      "io.github.kitlangton"          %% "zio-magic"                     % "0.2.3",
      "dev.zio"                       %% "zio-nio"                       % "1.0.0-RC10",
      "dev.zio"                       %% "zio-interop-cats"              % "2.3.1.0",
      "dev.zio"                       %% "zio-logging"                   % "0.5.8",
      "dev.zio"                       %% "zio"                           % zioVersion,
      "dev.zio"                       %% "zio-test"                      % zioVersion % "test",
      "dev.zio"                       %% "zio-test-sbt"                  % zioVersion % "test",
      "dev.zio"                       %% "zio-test-magnolia"             % zioVersion % "test"
    )
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
scalacOptions in Global += "-Ymacro-annotations"

scalafixScalaBinaryVersion in ThisBuild := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"
