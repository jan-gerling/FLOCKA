
name := "flocka"

version := "1.0"

scalaVersion := "2.12.6"
resolvers += Resolver.url("bintray-sbt-plugins", url("http://dl.bintray.com/sbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)

lazy val akkaVersion = "2.5.19"

libraryDependencies ++= Seq(

  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.1.8",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.1.8",
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "com.thoughtworks.binding" %% "futurebinding" % "latest.release",
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.1",
  "com.github.scullxbones" %% "akka-persistence-mongo-rxmongo" % "2.2.6",
  "org.apache.logging.log4j" % "log4j-core" % "2.11.2",
  "org.apache.logging.log4j" % "log4j-api" % "2.11.2"
	//"com.typesafe.akka" %% "akka-persistence-dynamodb" % "1.1.1"
)
