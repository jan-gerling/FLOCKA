package org.flocka.sagas

import java.util.concurrent.TimeoutException

import akka.actor.{ActorLogging, ActorPath, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.ServiceBasics.MessageTypes.{Command, Event, Query}
import org.flocka.Utils.{PushOutHashmapQueueBuffer, PushOutQueueBuffer}
import org.flocka.sagas.SagaExecutionControllerComs.{Execute, Executing, SagaAborted, SagaCompleted, StepCompleted, StepFailed, StepRollbackCompleted}

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


//Possible State Machine states
object SagaExecutionControllerSMState extends Enumeration {
  val STATE_PRE_EXEC, STATE_GO, STATE_ROLLBACK, STATE_POST_EXEC_SUCC, STATE_POST_EXEC_FAIL = Value
}

object SagasExecutionControllerActor {
  def props(): Props = Props(new SagasExecutionControllerActor())
}

case class SagaExecutionControllerState(saga: Saga, concurrentOperationsIndex: Int, state: SagaExecutionControllerSMState.Value, entityId: Long, currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations: PushOutHashmapQueueBuffer[Long, Event] = currentOperations

  def updated(event: Event): SagaExecutionControllerState = event match {
    case Executing(saga: Saga, operationId) =>
      doneOperations.push(operationId, event)
      copy(saga, 0, SagaExecutionControllerSMState.STATE_GO, entityId, currentOperations)
    case StepRollbackCompleted(step: Int) =>
      copy(saga, step - 1, state, entityId, currentOperations)
    case StepCompleted(step: Int) =>
      copy(saga, step + 1, state, entityId, currentOperations)
    case SagaCompleted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC, entityId, currentOperations)
    case StepFailed() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_ROLLBACK, entityId, currentOperations)
    case SagaAborted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_POST_EXEC_FAIL, entityId, currentOperations)
  }

}

class SagasExecutionControllerActor() extends PersistentActorBase with ActorLogging {
  override var state: PersistentActorState = SagaExecutionControllerState(Saga(), 0, SagaExecutionControllerSMState.STATE_PRE_EXEC, 0L, new PushOutHashmapQueueBuffer[Long, Event](5)) // For some reason i cant use _, if someone knows a fix, please tell
  val config: Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")
  val maxNumTimeoutsAllowed: Int = config.getInt("saga-execution-controller.max-num-timeouts-allowed")
  val connectionTimeout: Int = config.getInt("saga-execution-controller.connection-timeout")
  val maxConnectionTimeout: Int = config.getInt("saga-execution-controller.max-connection-timeout")
  context.setReceiveTimeout(passivateTimeout)
  val system = context.system

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event =>
      updateState(evt)
    case RecoveryCompleted =>
      execute()
    case SnapshotOffer(_, snapshot: SagaExecutionControllerState) => state = snapshot
  }

  def getSagaExecutionControllerState(): SagaExecutionControllerState = {
    state match {
      case state@SagaExecutionControllerState(_, _, _, _, _) => return state
      case _ => throw new Exception("Invalid SagaExecutionController state type for SagaExecutionControllerActor: " + persistenceId + ".A state ActorState.SagaExecutionControllerState type was expected, but " + state.toString + " was found.")
    }
  }


  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case Execute(_, saga, _) =>
        return saga.dagOfOps.size() != 0 && saga.dagOfOps.get(0).size() != 0
    }
    return true
  }

  override def buildResponseEvent(request: MessageTypes.Request): Event = {
    if (validateState(request) == false) {
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidSagaException(request.entityId.toString))
    }
    request match {
      case Execute(entityId: Long, saga: Saga, operationId: Long) =>
        if (getSagaExecutionControllerState().state == SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC)
          return SagaCompleted()
        else {
          return SagaAborted()

        }
      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  override protected def respond(request: MessageTypes.Request): Unit = {
    if (!validateState(request)) {
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidOrderException(request.key.toString))
    } else {
      request match {
        case command: MessageTypes.Command =>
          command match {
            case Execute(_, saga, operation) =>
              persistForSelf(Executing(saga, operation))
              execute()
              sendPersistentResponse(buildResponseEvent(command))
            case _ =>
              sendPersistentResponse(buildResponseEvent(command))
          }
        case query: MessageTypes.Query => sendResponse(buildResponseEvent(query))
      }
    }
  }

  def persistForSelf(evt: Event): Unit = {
    persist(evt) { evt => }
    updateState(evt) //dont wait for persist to finish to update state. persist lags behind current state
  }

  def doForwardOperation(op: SagaOperation, currIndex: Int, forwardId: Long, numTries: Int): Unit = {
    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(connectionTimeout * (numTries + 1) seconds)
    val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    val finalUri: Uri = op.pathForward + "/" + forwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)

    future.onComplete {
      case Success(res) =>
        if (op.forwardSuccessfulCondition.apply(res.entity.toString()))
          op.state = OperationState.SUCCESS_NO_ROLLBACK
        else
          op.state = OperationState.FAILURE

      case Failure(exception) =>
        if (exception.isInstanceOf[TimeoutException]) {
          if (numTries >= maxNumTimeoutsAllowed)
            op.state = OperationState.FAILURE
          else
            doForwardOperation(op, currIndex, forwardId, numTries + 1) //recurr to try again

        }
    }
  }

  def doBackwardOperation(op: SagaOperation, currIndex: Int, backwardId: Long, numTries: Int): Unit = {
    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(Math.min(connectionTimeout * (numTries + 1), maxConnectionTimeout) seconds) //Exponential backoff waits 5, 6, 8, 12, 20 seconds
    val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    val finalUri: Uri = op.pathInvert + "/" + backwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)

    future.onComplete {
      case Success(res) =>
        op.state = OperationState.SUCCESS_ROLLEDBACK
      case Failure(exception) =>
        if (exception.isInstanceOf[TimeoutException]) {
            doBackwardOperation(op, currIndex, backwardId, numTries + 1) //recur to try again
        }
    }

  }

  def execute(): Unit = {
    var sm_state = getSagaExecutionControllerState().state

    if (sm_state == SagaExecutionControllerSMState.STATE_PRE_EXEC || sm_state == SagaExecutionControllerSMState.STATE_POST_EXEC_FAIL || sm_state == SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC) {
      return
    }

    var finished = false
    val forwardId: Long = getSagaExecutionControllerState().saga.forwardId
    val backwardId: Long = getSagaExecutionControllerState().saga.backwardId

    while (!finished) {
      val opsAtIndex = getSagaExecutionControllerState().saga.dagOfOps.get(getSagaExecutionControllerState().concurrentOperationsIndex)
      if (getSagaExecutionControllerState().state == SagaExecutionControllerSMState.STATE_GO) {
        for (op <- opsAtIndex.asScala) {
          if (op.state == OperationState.UNPERFORMED)
            doForwardOperation(op, getSagaExecutionControllerState().concurrentOperationsIndex, forwardId, 0)
        }

        while (opsAtIndex.asScala.exists(op => op.state == OperationState.UNPERFORMED)) {
          Thread.sleep(50)
        }

        if (opsAtIndex.asScala.forall(op => op.state == OperationState.SUCCESS_NO_ROLLBACK)) {
          persistForSelf(StepCompleted(getSagaExecutionControllerState().concurrentOperationsIndex))
          if (getSagaExecutionControllerState().concurrentOperationsIndex == getSagaExecutionControllerState().saga.dagOfOps.asScala.length)
            finished = true
        } else { //If any of them are FAILURE
          persistForSelf(StepFailed())
        }

      } else {
        for (op <- opsAtIndex.asScala) {
          if (op.state == OperationState.SUCCESS_NO_ROLLBACK) {
            doBackwardOperation(op, getSagaExecutionControllerState().concurrentOperationsIndex, backwardId, 0)
          } else if (op.state == OperationState.UNPERFORMED) {
            doForwardOperation(op, getSagaExecutionControllerState().concurrentOperationsIndex, forwardId, 0)
            while (op.state == OperationState.UNPERFORMED) {
              Thread.sleep(50)
            }
            if (op.state == OperationState.SUCCESS_NO_ROLLBACK) {
              doBackwardOperation(op, getSagaExecutionControllerState().concurrentOperationsIndex, backwardId, 0)
            }
          } else {
            op.state = OperationState.SUCCESS_ROLLEDBACK //if op completed but not success. aka the one that triggered rollback
          }
        }
        while (opsAtIndex.asScala.exists(op => op.state == OperationState.UNPERFORMED || op.state == OperationState.SUCCESS_NO_ROLLBACK)) {
          Thread.sleep(50)
        }
        persistForSelf(StepRollbackCompleted(getSagaExecutionControllerState().concurrentOperationsIndex))
        if (getSagaExecutionControllerState().concurrentOperationsIndex == -1)
          finished = true
      }
    }

    if (getSagaExecutionControllerState().state == SagaExecutionControllerSMState.STATE_GO) {
      updateState(SagaCompleted())
    } else {
      updateState(SagaAborted())
    }
  }

}
