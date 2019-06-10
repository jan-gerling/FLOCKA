package org.flocka.Services.Order

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.{BackoffOnStopOptions, BackoffOpts, BackoffSupervisor}
import org.flocka.ServiceBasics.{ShardingBase}

import scala.concurrent.duration._

/**
  * Don't forget to configure the number of shards in order-service.conf
  */
object OrderSharding extends ShardingBase("Order", "order-service.conf"){
  val backoffOpts : BackoffOnStopOptions = BackoffOpts.onStop(Props(classOf[OrderRepository]), childName = "OrderRepo", minBackoff = 1.seconds, maxBackoff = 5.seconds, randomFactor = 0.2).withFinalStopMessage(_ == PoisonPill)
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

  override var seedPorts: Array[String] = Array("2571", "2572")
  override var publicSeedPort: String = "2571"
}