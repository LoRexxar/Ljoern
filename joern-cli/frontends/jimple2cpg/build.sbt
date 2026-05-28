name := "jimple2cpg"

dependsOn(
  Projects.dataflowengineoss  % "test->test",
  Projects.x2cpg              % "compile->compile;test->test",
  Projects.linterRules % ScalafixConfig
)

libraryDependencies ++= Seq(
  "io.shiftleft"  %% "codepropertygraph" % Versions.cpg,
  "com.github.javaparser" % "javaparser-core" % Versions.javaParser,
  "org.soot-oss"   % "soot"              % Versions.soot,
  "org.typelevel" %% "cats-core"         % Versions.catsCore,
  "org.scalatest" %% "scalatest"         % Versions.scalatest % Test,
  "org.benf"       % "cfr"               % Versions.cfr
)

enablePlugins(JavaAppPackaging, LauncherJarPlugin)
trapExit    := false
Test / fork := true
