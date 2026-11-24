ThisBuild / scalaVersion := "2.13.16"
name := "SmartStock"
version := "0.1.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.20",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.20" % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "com.lihaoyi" %% "ujson" % "4.4.3",
  "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.5",
  "org.slf4j" % "slf4j-simple" % "1.7.36"
)
