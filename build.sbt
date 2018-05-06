// in the name of ALLAH

organization := "com.bisphone"

name := "sarf" // Simple Abstraction for Remote Function

version := "0.8.1"

scalaVersion := "2.12.5"

crossScalaVersions := Seq("2.11.11", "2.12.5")

scalacOptions ++= Seq("-feature", "-deprecation", "-language:postfixOps")

def akka (
    module : String,
    version: String = "2.5.6"
) = "com.typesafe.akka" %% module % version

fork := true

libraryDependencies ++= Seq(
    "com.bisphone" %% "akkastream" % "0.4.3",
    // "com.bisphone" %% "std" % "0.12.0",
    "com.bisphone" %% "std" % "0.13.0",
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "ch.qos.logback" % "logback-classic" % "1.1.7" % Provided
)

libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.1" % Test,
    "com.bisphone" %% "testkit" % "0.4.1" % Test,
    akka("akka-testkit") % Test,
    akka("akka-stream-testkit") % Test
)
