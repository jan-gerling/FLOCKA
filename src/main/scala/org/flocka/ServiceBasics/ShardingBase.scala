package org.flocka.ServiceBasics

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ShardRegion}
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Request

/**
  * Contains functions and configuration relating to the sharding of Stock.
  *
  * To create a new Sharded service an equivalent of this object must be created.
  * Start by changing the entityProps parameter or ClusterSharding.start().
  * We recomend sharding by repository, otherwise, you will have to define your own extractShardId and extractEntityId
  */
abstract case class ShardingBase(name: String, configPath: String) {
  def startSharding(system: ActorSystem): ActorRef

  final val extractEntityId: ShardRegion.ExtractEntityId = {
    case request: Request =>
      (request.entityId.toString, request)
    case _ => throw new IllegalArgumentException()
  }

  final val extractShardId: ShardRegion.ExtractShardId = {
    case request: Request =>
      (IdResolver.extractShardId(request.key)).toString
    case _ => throw new IllegalArgumentException()
  }

  final val shardName: String = name
  final val configName: String = configPath
  final val config: Config = ConfigFactory.load(configName)

  /**
  Probably only used for testing
   */
  var seedPorts: Array[String]
  var publicSeedPort: String

  final val numShards: Int = config.getInt("sharding.numshards")
  final val exposedPort: Int = config.getInt("sharding.exposed-port")
  final val clusterName: String = config.getString("clustering.cluster.name")
  final val hostName: String = config.getString("akka.remote.netty.tcp.hostname")
}