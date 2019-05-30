package org.flocka.sagas

import akka.actor.{ActorPath}
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event

object SagaExecutionControllerComs {


  final case class LoadSaga(sagaId: Long, saga: Saga, operation: Long) extends MessageTypes.Command {
    val entityId: Long = sagaId
    override val key: Long = sagaId
  }

  final case class Execute(sagaId: Long, operation: Long) extends MessageTypes.Command {
    val entityId: Long = sagaId
    override val key: Long = sagaId
  }


  case class SagaLoaded(saga: Saga, operation: Long) extends Event

  case class Executing(operation: Long) extends Event

  case class StepCompleted(newStep: Int) extends Event

  case class StepRollbackCompleted(step: Int) extends Event

  case class StepFailed() extends Event

  case class SagaCompleted() extends Event

  case class SagaAborted() extends Event

}
