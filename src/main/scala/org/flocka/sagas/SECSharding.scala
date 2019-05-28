package org.flocka.sagas

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Request

object SECSharding {
  def startSharding(system: ActorSystem): ActorRef =
    ClusterSharding(system).start(
      typeName = shardName,
      entityProps = SagasExecutionControllerActor.props(),
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
      (request.key % numShards).toString
    case _ => throw new IllegalArgumentException()
  }

  val conf: Config = ConfigFactory.load("order-service.conf")
  val numShards = conf.getInt("sec.numshards")

  val shardName: String = conf.getString("sec.shard-name")
}
