package org.flocka

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.{ClusterSharding}
import akka.pattern.ask
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.Services.Order.{OrderService, OrderSharding}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ShardedOrderServiceApp extends App {
  def logImportant(toLog: String) = println("==========================\n" + toLog + "\n=============================")

  override def main(args: Array[String]): Unit = {
    val publicPort: String = "2571"

    Seq("2571", "2572") foreach { port =>

      val configName: String = "order-service.conf"
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load(configName))

      // Create an Akka system for each port
      implicit val system = ActorSystem(config getString "clustering.cluster.name", config)

      //Journal is currently leveldb, it is used to persist events of PersistentActors
      val pathToJournal : ActorPath = ActorPath.fromString("akka.tcp://" + config.getString("clustering.cluster.name")+
        "@"+config.getString("akka.remote.netty.tcp.hostname")+":" + publicPort +"/user/store")
      startupSharedJournal(system, startStore = (port == publicPort), path = pathToJournal)(system.dispatcher)

      //Start sharding system locally, this will create a ShardingRegion
      OrderSharding.startOrderSharding(system)

      //Start rest server if on correct port
      attemptStartRest(port)

      //sleep needed for proper boot of "cluster"
      Thread.sleep(5000)
    }

    def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath)(implicit executionContext: ExecutionContext): Unit = {
      // Start the shared journal one one node (don't crash this SPOF)
      // This will not be needed with a distributed journal
      if (startStore) {
        system.actorOf(Props[SharedLeveldbStore], "store")
        logImportant("Started Journal!")
      }
      // register the shared journal
      implicit val timeout = Timeout(15.seconds)
      val f = (system.actorSelection(path) ? Identify(None))
      f.onSuccess {
        case ActorIdentity(_, Some(ref)) =>
          SharedLeveldbJournal.setStore(ref, system)
          logImportant("Found journal!")
        case _ =>
          system.log.error("Shared journal not started at {}", path)
          system.terminate()
      }
      f.onFailure {
        case _ =>
          system.log.error("Lookup of shared journal at {} timed out", path)
          system.terminate()
      }
    }
  }

  def attemptStartRest(port: String)(implicit system: ActorSystem): Unit = {
    val publicPort: String = "2571"

    if (port == publicPort) {
      //Get ActorRef of user Shard Region
      val orderShard = ClusterSharding(system).shardRegion(OrderSharding.shardName)
      //Start rest service
      OrderService.bind(orderShard, OrderSharding.exposedPort, system.dispatcher).onComplete(
        Success => logImportant("Started server!")
      )(system.dispatcher)
    }
  }
}
