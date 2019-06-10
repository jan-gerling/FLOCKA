package org.flocka.Services.Stock

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.{BackoffOnStopOptions, BackoffOpts, BackoffSupervisor}
import org.flocka.ServiceBasics.ShardingBase

import scala.concurrent.duration._
/**
  * Don't forget to configure the number of shards in stock-service.conf
  */
object StockSharding extends ShardingBase("Stock", "stock-service.conf") {
  val backoffOpts : BackoffOnStopOptions = BackoffOpts.onStop(Props(classOf[StockRepository]), childName = "StockRepo", minBackoff = 3.seconds, maxBackoff = 30.seconds, randomFactor = 0.5).withFinalStopMessage(_ == PoisonPill)
  val supervisorProps = BackoffSupervisor.props(backoffOpts)


  override def startSharding(system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = supervisorProps,
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  override var seedPorts: Array[String] = Array("2561", "2562")
  override var publicSeedPort: String = "2561"
}

