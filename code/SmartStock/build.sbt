
ThisBuild / scalaVersion := "2.13.16"

name := "SmartStock"

version := "0.1.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.20",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.20" % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)
