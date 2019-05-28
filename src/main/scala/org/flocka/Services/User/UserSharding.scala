package org.flocka.Services.User

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import org.flocka.ServiceBasics.{ ShardingBase}

/**
  * Don't forget to configure the number of shards in user-service.conf
  */
object UserSharding extends ShardingBase("User", "user-service.conf"){
  override def startSharding(system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = UserRepository.props(),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  override var seedPorts: Array[String] = Array("2551", "2552")
  override var publicSeedPort: String = "2551"
}