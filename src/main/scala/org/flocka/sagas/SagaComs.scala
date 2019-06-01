package org.flocka.sagas

import akka.actor.ActorPath
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event

object SagaComs {
  final case class ExecuteSaga(saga: Saga) extends MessageTypes.Command{
    val entityId: Long = saga.id
    override val key: Long = saga.id
  }

  case class SagaStored(saga: Saga, requesterPath : ActorPath) extends Event
  case class SagaCompleted(saga: Saga) extends Event
  case class SagaFailed(saga: Saga) extends Event
}