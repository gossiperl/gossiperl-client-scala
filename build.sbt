name := "gossiperl-client-scala"

version := "1.0.0"

scalaVersion := "2.10.4"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
 "org.apache.thrift" % "libthrift" % "0.9.2",
 "com.typesafe.akka" %% "akka-actor" % "2.3.9",
 "com.gossiperl" % "gossiperl-core" % "2.1.0",
 "ch.qos.logback" % "logback-classic" % "1.1.3",
 "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
 "org.scalatest" %% "scalatest" % "2.0" % "test"
)