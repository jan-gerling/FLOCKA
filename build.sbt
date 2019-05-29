name := "flocka"

version := "1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.22"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
	"com.typesafe.akka" %% "akka-http" % "10.1.8",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.1.8",
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "com.thoughtworks.binding" %% "futurebinding" % "latest.release",
  "com.typesafe.akka" %% "akka-cluster" % "2.5.22",
  "com.typesafe.akka" %% "akka-cluster-sharding" % "2.5.22",
  "org.iq80.leveldb" % "leveldb" % "0.7",
  "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.1",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.8"
)
