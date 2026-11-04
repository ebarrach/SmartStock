ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "be.unamur.smartstock"
ThisBuild / version := "0.1.0"
name := "smartstock-agent"
Compile / mainClass := Some("smartstock.Main")
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.20",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.20" % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "ch.qos.logback" % "logback-classic" % "1.5.21"
)
