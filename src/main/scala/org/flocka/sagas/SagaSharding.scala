package org.flocka.sagas

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import org.flocka.ServiceBasics.ShardingBase

object SagaSharding extends ShardingBase("", "saga-execution-controller.conf") {
  def startSharding(system: ActorSystem): ActorRef =
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = SagaStorage.props(),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

  override var seedPorts: Array[String] = Array("2591", "2592")
  override var publicSeedPort: String = "2591"

  override val numShards: Int = config.getInt("sharding.numshards")
  override val shardName: String = config.getString("sharding.shard-name")
}