package org.flocka

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.ask
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.Services.Payment.{PaymentRepository, PaymentService, PaymentSharding}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ShardedPaymentServiceApp extends App {
  def logImportant(toLog: String) = println("==========================\n" + toLog + "\n=============================")

  override def main(args: Array[String]): Unit = {
    Seq("2581", "2582") foreach { port =>

      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load())

      // Create an Akka system for each port
      implicit val system = ActorSystem(config getString "clustering.cluster.name", config)


      //Journal is currently leveldb, it is used to persist events of PersistentActors
      val pathToJournal : ActorPath =  ActorPath.fromString("akka.tcp://" + config.getString("clustering.cluster.name")+ "@"+config.getString("akka.remote.netty.tcp.hostname")+":2581/user/store")
      startupSharedJournal(system, startStore = (port == "2581"), path =pathToJournal)(system.dispatcher)


      //Start sharding system locally, this will create a ShardingRegion
      ClusterSharding(system).start(
        typeName = PaymentSharding.shardName,
        entityProps = PaymentRepository.props,
        settings = ClusterShardingSettings(system),
        extractEntityId = PaymentSharding.extractEntityId,
        extractShardId = PaymentSharding.extractShardId)

      //Start rest server if on correct port
      attemptStartRest(port, config)

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
        case a@_ =>
          print(a)
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

  def attemptStartRest(port: String, config: Config)(implicit system: ActorSystem): Unit = {
    if (port == "2581") {
      //Get ActorRef of payment Shard Region
      val paymentShard = ClusterSharding(system).shardRegion(PaymentSharding.shardName)
      //Start rest service
      PaymentService.bind(paymentShard, config getInt "payment.exposed-port", system.dispatcher).onComplete(
        Success => logImportant("Started server!")
      )(system.dispatcher)
    }
  }
}
