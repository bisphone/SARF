// in the name of ALLAH

organization := "com.bisphone"

name := "sarf" // Simple Abstraction for Remote Function

version := "0.7.3-SNAPSHOT"

scalaVersion := "2.11.11"

scalacOptions ++= Seq("-feature", "-deprecation", "-language:postfixOps")

def akka (
    module : String,
    version: String = "2.5.1"
) = "com.typesafe.akka" %% module % version

fork := true

libraryDependencies ++= Seq(
    "com.bisphone" %% "akkastream" % "0.4.2",
    "com.bisphone" %% "std" % "0.8.3",
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "ch.qos.logback" % "logback-classic" % "1.1.7" % Provided
)

libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.1" % Test,
    // "com.bisphone" %% "testkit" % "0.4.0" % Test,
    akka("akka-testkit") % Test,
    akka("akka-stream-testkit") % Test
)
