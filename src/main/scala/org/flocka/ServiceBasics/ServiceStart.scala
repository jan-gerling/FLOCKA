package org.flocka.ServiceBasics

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.ClusterSharding
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import akka.pattern.ask
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ServiceStart {
  def startService(shardingStrategy: ShardingBase, service: ServiceBase): Unit ={
    implicit val shardStrategy: ShardingBase = shardingStrategy

    shardStrategy.seedPorts foreach { port =>
      val config: Config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(shardStrategy.config)

      // Create an Akka system for each port
      implicit val system = ActorSystem(shardStrategy.clusterName, config)

      //Journal is currently leveldb, it is used to persist events of PersistentActors
      val path = "akka.tcp://" + shardStrategy.clusterName + "@" + shardStrategy.hostName + ":" + shardStrategy.publicSeedPort + "/user/store"
      val pathToJournal : ActorPath =  ActorPath.fromString(path)

      startupSharedJournal(system, startStore = port == shardStrategy.publicSeedPort, path =pathToJournal, service)(system.dispatcher)

      //Start sharding system locally, this will create a ShardingRegion
      shardStrategy.startSharding(system)

      //Start rest server if on correct port
      attemptStartRest(port, service)

      //sleep needed for proper boot of "cluster"
      Thread.sleep(5000)
    }
  }

  private def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath, service: ServiceBase)(implicit executionContext: ExecutionContext): Unit = {
    // Start the shared journal one one node (don't crash this SPOF)
    // This will not be needed with a distributed journal
    if (startStore) {
      system.actorOf(Props[SharedLeveldbStore], "store")
      logImportant("Started Journal for service: " + service.getClass)
    }
    // register the shared journal
    implicit val timeout = Timeout(15.seconds)
    val futureActor = system.actorSelection(path) ? Identify(None)
    futureActor.onSuccess {
      case ActorIdentity(_, Some(ref)) =>
        SharedLeveldbJournal.setStore(ref, system)
        logImportant("Found journal for service: " + service.getClass)
      case _ =>
        system.log.error("Shared journal not started at {}" + path + " for service: " + service.getClass)
        system.terminate()
    }
    futureActor.onFailure {
      case _ =>
        system.log.error("Lookup of shared journal at {} timed out" + path + " for service: " + service.getClass)
        system.terminate()
    }
  }

  private def attemptStartRest(port: String, service: ServiceBase)(implicit system: ActorSystem, shardStrategy: ShardingBase): Unit = {
    if (port == shardStrategy.publicSeedPort) {
      //Get ActorRef of stock Shard Region
      val stockShard = ClusterSharding(system).shardRegion(shardStrategy.shardName)
      //Start rest service
      service.bind(stockShard, shardStrategy.exposedPort, system.dispatcher).onComplete(
        Success => logImportant("Started server for service: " + service.getClass)
      )(system.dispatcher)
    }
  }

  def logImportant(toLog: String) =
    println("==========================\n" + toLog + "\n=============================")
}