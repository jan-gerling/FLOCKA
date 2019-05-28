package org.flocka.Services.Payment

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import org.flocka.ServiceBasics.{ShardingBase}

/**
  * Don't forget to configure the number of shards in order-service.conf
  */
object PaymentSharding extends ShardingBase("Payment", "payment-service.conf"){
  override def startSharding(system: ActorSystem): ActorRef =
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = PaymentRepository.props(),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

  override var seedPorts: Array[String] = Array("2581", "2582")
  override var publicSeedPort: String = "2581"
}