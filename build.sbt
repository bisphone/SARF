// in the name of ALLAH

organization := "com.bisphone"

name := "sarf" // Simple Abstraction for Remote Function

version := "0.7.0-SNAPSHOT"

scalaVersion := "2.11.8"

scalacOptions ++= Seq("-feature", "-deprecation", "-language:postfixOps")

def akka (
   module: String,
   version: String = "2.4.4"
) = "com.typesafe.akka" %% module % version

fork := true

libraryDependencies ++= Seq(
   "com.bisphone" %% "akkastream" % "0.4.0-SNAPSHOT",
   "com.bisphone" %% "std" % "0.7.5-SNAPSHOT",
   "ch.qos.logback" % "logback-classic" % "1.1.7"
)

libraryDependencies ++= Seq(
   "org.scalatest" %% "scalatest" % "2.2.6" % Test,
   "com.bisphone" %% "beta-testkit" % "0.1.0" % Test,
   akka("akka-testkit") % Test,
   akka("akka-stream-testkit") % Test
)
