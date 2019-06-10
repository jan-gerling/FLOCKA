package org.flocka.sagas

import akka.actor.{Actor, ActorPath, ActorRef, ActorSystem, Cancellable, Identify, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}

import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.sagas.SagaComs._

import scala.concurrent.ExecutionContext

object SagaStorage {
  val MAX_NUM_TRIES = 3

  def props(): Props = Props(new SagaStorage())
}


class SagaStorage extends Actor {

  val config : Config = ConfigFactory.load("saga-execution-controller.conf")


  implicit val system: ActorSystem = context.system
  implicit val executor: ExecutionContext = context.dispatcher


  val receive: Receive = {
    case ExecuteSaga(saga: Saga, requesterPath: ActorRef) =>
        val eventExecution: Event = saga.execute()
        requesterPath ! eventExecution

    case message@ _ => throw new IllegalArgumentException(message.toString)
  }

}