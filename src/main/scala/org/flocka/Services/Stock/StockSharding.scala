package org.flocka.Services.Stock

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import org.flocka.ServiceBasics.{ShardingBase}

/**
  * Don't forget to configure the number of shards in stock-service.conf
  */
object StockSharding extends ShardingBase("Stock", "stock-service.conf") {
  override def startSharding(system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = StockRepository.props(),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  override var seedPorts: Array[String] = Array("2561", "2562")
  override var publicSeedPort: String = "2561"
}