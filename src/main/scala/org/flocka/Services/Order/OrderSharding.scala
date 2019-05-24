package org.flocka.Services.Order

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import com.typesafe.config.{ConfigFactory}
import org.flocka.ServiceBasics.IdManager
import org.flocka.ServiceBasics.MessageTypes.Request

/**
  * Contains functions and configuration relating to the sharding of Order.
  *
  * To create a new Sharded service an equivalent of this object must be created.
  * Start by changing the entityProps parameter of ClusterSharding.start().
  * We recommend sharding by repository, otherwise, you will have to define your own extractShardId and extractEntityId
  *
  * Don't forget to configure the number of shards in application.conf
  */
object OrderSharding {
  def startOrderSharding(system: ActorSystem): ActorRef =
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = OrderRepository.props(),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case request: Request =>
      (request.entityId.toString, request)
    case _ => throw new IllegalArgumentException()
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case request: Request =>
      (IdManager.extractShardId(request.key)).toString
    case _ => throw new IllegalArgumentException()
  }

  val configName: String = "order-service.conf"
  val config = ConfigFactory.load(configName)
  val numShards = config.getInt("sharding.numshards")
  val exposedPort = config.getInt("sharding.exposed-port")

  val shardName: String = "Order"
}