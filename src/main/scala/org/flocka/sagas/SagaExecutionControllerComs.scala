package org.flocka.sagas

import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event

object SagaExecutionControllerComs {
  final case class ExecuteSaga(saga: Saga) extends MessageTypes.Command{
    val entityId: Long = saga.id
    override val key: Long = saga.id
  }

  case class SagaCreated(saga: Saga) extends Event
  case class SagaCompleted() extends Event
  case class SagaAborted() extends Event

}