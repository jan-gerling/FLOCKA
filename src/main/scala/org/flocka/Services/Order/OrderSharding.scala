package org.flocka.Services.Order

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import org.flocka.ServiceBasics.{ShardingBase}

/**
  * Don't forget to configure the number of shards in order-service.conf
  */
object OrderSharding extends ShardingBase("Order", "order-service.conf"){
  override def startSharding(system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = OrderRepository.props(),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  override var seedPorts: Array[String] = Array("2571", "2572")
  override var publicSeedPort: String = "2571"
}