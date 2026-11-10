ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "be.unamur.smartstock"
ThisBuild / version := "0.1.0"
name := "smartstock-agent"
assembly / mainClass := Some("smartstock.ServiceMain")
assembly / assemblyJarName := "smartstock-agent.jar"
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _ @ _*) => MergeStrategy.filterDistinctLines
  case PathList("META-INF", _ @ _*) => MergeStrategy.discard
  case "module-info.class" => MergeStrategy.discard
  case path => (assembly / assemblyMergeStrategy).value(path)
}
Compile / mainClass := Some("smartstock.ServiceMain")
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.20",
  "io.spray" %% "spray-json" % "1.3.6",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.20" % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "ch.qos.logback" % "logback-classic" % "1.5.21"
)

Test / parallelExecution := false
