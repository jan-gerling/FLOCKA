package org.flocka.Services.Payment

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Request

/**
  * Contains functions and configuration relating to the sharding of Payment.
  *
  * To create a new Sharded service an equivalent of this object must be created.
  * Start by changing the entityProps parameter or ClusterSharding.start().
  * We reccomend sharding by repository, otherwise, you will have to define your own extractShardId and extractEntityId
  *
  * Dont forget to configure the number of shards in application.conf
  */
object PaymentSharding {

  def startPaymentSharding(system: ActorSystem): ActorRef =
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = PaymentRepository.props(),
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

  val conf: Config = ConfigFactory.load()
  val numShards = conf.getInt("payment.numshards")

  val shardName: String = "Payment"
}