package org.flocka.sharding

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import akka.pattern.ask
import org.flocka.Services.User.{UserActor, UserService, UserSharding}

import scala.concurrent.ExecutionContext

object ShardedAppTest extends App {
  override def main(args: Array[String]): Unit = {
    Seq("2551", "2552") foreach { port =>
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load())

      // Create an Akka system
      implicit val system = ActorSystem(config getString "clustering.cluster.name", config)

      startupSharedJournal(system, startStore = (port == "2551"), path =
        ActorPath.fromString("akka.tcp://" + config.getString("clustering.cluster.name")+ "@"+config.getString("akka.remote.netty.tcp.hostname")+":2551/user/store"))
      ClusterSharding(system).start(
        typeName = UserSharding.shardName,
        entityProps = UserActor.props,
        settings = ClusterShardingSettings(system),
        extractEntityId = UserSharding.extractEntityId,
        extractShardId = UserSharding.extractShardId)

      if (port == "2551") {
        val userShard = ClusterSharding(system).shardRegion(UserSharding.shardName)
        UserService.bind(userShard, config getInt "user.exposed-port", system.dispatcher).onComplete(
          Success => System.out.println("REEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE\nSTARTED SERVER")
        )(system.dispatcher)
      }

      Thread.sleep(5000)
      System.out.println("REEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE\nDONE SLEPEEEING\nREEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE")
    }

    def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath): Unit = {
      // Start the shared journal one one node (don't crash this SPOF)
      // This will not be needed with a distributed journal
      if (startStore) {
        system.actorOf(Props[SharedLeveldbStore], "store")
        System.out.println("===========\nSSTART JOURNAL\n==============")
      }
      // register the shared journal
      import system.dispatcher
      implicit val timeout = Timeout(15.seconds)
      val f = (system.actorSelection(path) ? Identify(None))
      f.onSuccess {
        case ActorIdentity(_, Some(ref)) =>
          SharedLeveldbJournal.setStore(ref, system)
          System.out.println("===========\nSUCCESSFULLY FOUND JOURNAL\n==============")
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
}
