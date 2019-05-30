package org.flocka.sagas

import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event

object SagaExecutionControllerComs {
  final case class ExecuteSaga(saga: Saga) extends MessageTypes.Command{
    val entityId: Long = saga.id
    override val key: Long = saga.id
  }

  case class SagaStored(saga: Saga) extends Event
  case class SagaCompleted(saga: Saga) extends Event
  case class SagaFailed(saga: Saga) extends Event
}