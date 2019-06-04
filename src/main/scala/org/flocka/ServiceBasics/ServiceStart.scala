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
  def startService(shardingStrategy: ShardingBase, service: ServiceBase): Unit = {
    implicit val shardStrategy: ShardingBase = shardingStrategy

    val config: Config = ConfigFactory.load(shardStrategy.config)

    // Create an Akka system for each port
    implicit val system = ActorSystem(shardStrategy.clusterName, config)

    //Start sharding system locally, this will create a ShardingRegion
    shardStrategy.startSharding(system)

    val shard = ClusterSharding(system).shardRegion(shardStrategy.shardName)
    //Start rest service
    service.bind(shard, system.dispatcher).onComplete(
      Success => logImportant("Started server for service: " + service.getClass)
    )(system.dispatcher)
  }

  def logImportant(toLog: String) =
    println("==========================\n" + toLog + "\n=============================")
}
