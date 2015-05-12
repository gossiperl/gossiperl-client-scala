name := "gossiperl-client-scala"

version := "1.1.1"

scalaVersion := "2.10.5"

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.mavenLocal
)

libraryDependencies ++= Seq(
 "org.apache.thrift" % "libthrift" % "0.9.2",
 "com.typesafe.akka" %% "akka-actor" % "2.3.11",
 "com.gossiperl" % "gossiperl-core" % "2.1.0",
 "ch.qos.logback" % "logback-classic" % "1.1.3",
 "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
 "org.scalatest" %% "scalatest" % "2.0" % "test"
)