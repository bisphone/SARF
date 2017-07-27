// in the name of ALLAH

organization := "com.bisphone"

name := "sarf" // Simple Abstraction for Remote Function

version := "0.7.2"

scalaVersion := "2.11.11"

scalacOptions ++= Seq("-feature", "-deprecation", "-language:postfixOps")

def akka (
   module: String,
   version: String = "2.5.1"
) = "com.typesafe.akka" %% module % version

fork := true

libraryDependencies ++= Seq(
   "com.bisphone" %% "akkastream" % "0.4.2",
   "com.bisphone" %% "std" % "0.8.3",
   "ch.qos.logback" % "logback-classic" % "1.1.7"
)

libraryDependencies ++= Seq(
   "org.scalatest" %% "scalatest" % "2.2.6" % Test,
   "com.bisphone" %% "testkit" % "0.4.0" % Test,
   akka("akka-testkit") % Test,
   akka("akka-stream-testkit") % Test
)
