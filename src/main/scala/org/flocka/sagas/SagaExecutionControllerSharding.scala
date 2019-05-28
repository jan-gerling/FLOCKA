package org.flocka.sagas

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Request

object SagaExecutionControllerSharding {
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

  val conf: Config = ConfigFactory.load("saga-execution-controller.conf")
  val numShards = conf.getInt("sharding.numshards")

  val shardName: String = conf.getString("sharding.shard-name")
}
