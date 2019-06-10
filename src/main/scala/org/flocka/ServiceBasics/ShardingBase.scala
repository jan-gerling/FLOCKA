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
    case other@_ => throw new IllegalArgumentException(other.toString)
  }

  final val extractShardId: ShardRegion.ExtractShardId = {
    case request: Request =>
      (IdResolver.extractShardId(request.key)).toString
    case _ => throw new IllegalArgumentException()
  }

  /**
    * Alter with care if necessary!
    */
  def shardName: String = name
  final val configName: String = configPath
  final val config: Config = ConfigFactory.load(configName)

  /**
  Probably only used for testing
   */
  var seedPorts: Array[String]
  var publicSeedPort: String

  def numShards: Int = config.getInt("sharding.numshards")
  def clusterName: String = config.getString("clustering.cluster.name")
  def hostName: String = config.getString("akka.remote.netty.tcp.hostname")
}