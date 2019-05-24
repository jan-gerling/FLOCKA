package org.flocka.sagas

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import akka.pattern.ask
import org.flocka.Services.User.IdManager
import org.flocka.sagas.SagaExecutionControllerComs.RequestExecution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object SECTest extends App {
  def logImportant(toLog: String) = println("==========================\n" + toLog + "\n=============================")


  def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath)(implicit executionContext: ExecutionContext): Unit = {
    // Start the shared journal one one node (don't crash this SPOF)
    // This will not be needed with a distributed journal
    if (startStore) {
      system.actorOf(Props[SharedLeveldbStore], "store")
      logImportant("Started Journal!")
    }
    // register the shared journal
    implicit val timeout = Timeout(15.seconds)
    val f = system.actorSelection(path) ? Identify(None)
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

  override def main(args: Array[String]): Unit = {
    Seq("2551", "2552") foreach { port =>

      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load())

      // Create an Akka system for each port
      implicit val system = ActorSystem(config getString "clustering.cluster.name", config)


      //Journal is currently leveldb, it is used to persist events of PersistentActors
      val pathToJournal : ActorPath =  ActorPath.fromString("akka.tcp://" + config.getString("clustering.cluster.name")+ "@"+config.getString("akka.remote.netty.tcp.hostname")+":2551/user/store")
      startupSharedJournal(system, startStore = (port == "2551"), path =pathToJournal)(system.dispatcher)


      //Start sharding system locally, this will create a ShardingRegion
      ClusterSharding(system).start(
        typeName = SECSharding.shardName,
        entityProps = SagasExecutionControllerActor.props,
        settings = ClusterShardingSettings(system),
        extractEntityId = SECSharding.extractEntityId,
        extractShardId = SECSharding.extractShardId)

      //sleep needed for proper boot of "cluster"
      Thread.sleep(5000)
      if(port == "2552"){
        val testSaga: Saga = new Saga()
        val secShard = ClusterSharding(system).shardRegion(SECSharding.shardName)
        secShard ! RequestExecution(IdManager.generateId(100), testSaga)
      }
    }



  }

}
