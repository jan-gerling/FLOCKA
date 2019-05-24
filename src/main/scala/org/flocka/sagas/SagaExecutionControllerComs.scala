package org.flocka.sagas

import akka.actor.ActorRef
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event

object SagaExecutionControllerComs {


  final case class RequestExecution(sagaId: Long, saga: Saga) extends MessageTypes.Command{
    val entityId: Long = sagaId
    override val key: Long = sagaId
  }

  final case class ExecuteNextStep(sagaId: Long) extends MessageTypes.Command {
    val entityId: Long = sagaId
    override val key: Long = sagaId
  }



  case class SagaStarted(saga: Saga, cmdRequester: ActorRef) extends Event

  case class StepStarted(index: Int) extends Event

  case class StepCompleted(newStep: Int) extends Event

  case class RollbackStarted() extends Event

  case class SagaCompleted() extends Event

  case class SagaAborted() extends Event

}
