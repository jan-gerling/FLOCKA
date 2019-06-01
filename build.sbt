name := "flocka"

version := "1.0"

scalaVersion := "2.12.6"
resolvers += Resolver.url("bintray-sbt-plugins", url("http://dl.bintray.com/sbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)

lazy val akkaVersion = "2.5.22"

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
  "com.typesafe.akka" %% "akka-cluster" % "2.5.22",
  "com.typesafe.akka" %% "akka-cluster-sharding" % "2.5.22",
  "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.1",
  "com.github.scullxbones" %% "akka-persistence-mongo-scala" % "2.2.6",
  "org.mongodb.scala" %% "mongo-scala-bson" % "1.0.0",
  "io.netty" % "netty-all" % "4.0.4.Final" //mongodb driver docs claim this to be necessary for ssl
)

