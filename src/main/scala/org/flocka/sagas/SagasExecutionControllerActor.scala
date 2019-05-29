package org.flocka.sagas

import java.util.concurrent.TimeoutException

import akka.actor.{ActorLogging, ActorPath, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.ServiceBasics.MessageTypes.{Command, Event, Query}
import org.flocka.Services.User.{UserRepositoryState, UserState}
import org.flocka.Utils.{PushOutBuffer, PushOutHashmapQueueBuffer, PushOutQueueBuffer}
import org.flocka.sagas.SagaExecutionControllerComs.{Execute, Executing, LoadSaga, SagaAborted, SagaCompleted, SagaLoaded, StepCompleted, StepFailed, StepRollbackCompleted}

import scala.collection.mutable
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

case class SagaExecutionControllerState(saga: Saga, concurrentOperationsIndex: Int, state: SagaExecutionControllerSMState.Value, entityId: Long, requesterPath: ActorPath) extends PersistentActorState {

  override var doneOperations = new PushOutHashmapQueueBuffer[Long, Event](0)

  def updated(event: Event): SagaExecutionControllerState = event match {
    case SagaLoaded(saga: Saga, requesterPath: ActorPath) =>
      copy(saga, 0, SagaExecutionControllerSMState.STATE_GO, entityId, requesterPath)
    case Executing() =>
      copy(saga, concurrentOperationsIndex, state, entityId, requesterPath)
    case StepRollbackCompleted(step: Int) =>
      copy(saga, step - 1, state, entityId, requesterPath)
    case StepCompleted(step: Int) =>
      copy(saga, step + 1, state, entityId, requesterPath)
    case SagaCompleted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC, entityId, requesterPath)
    case StepFailed() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_ROLLBACK, entityId, requesterPath)
    case SagaAborted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_POST_EXEC_FAIL, entityId, requesterPath)
  }

}

class SagasExecutionControllerActor() extends PersistentActorBase with ActorLogging {
  override var state: PersistentActorState = new SagaExecutionControllerState(new Saga(), 0, SagaExecutionControllerSMState.STATE_PRE_EXEC, 0L, self.path) // For some reason i cant use _, if someone knows a fix, please tell
  val config: Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")
  val loadBalancerURI = config.getString("clustering.loadbalancer.uri")
  val MAX_NUM_TIMEOUTS_ALLOWED = config.getInt("saga-execution-controller.max-num-timeouts-allowed")
  val connectionTimeout: Int = config.getInt("saga-execution-controller.connection-timeout")
  val maxConnectionTimeout: Int = config.getInt("saga-execution-controller.max-connection-timeout")
  context.setReceiveTimeout(passivateTimeout)
  val system = context.system


  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => {
      updateState(evt)
    }
    case RecoveryCompleted =>
      execute()
    case SnapshotOffer(_, snapshot: SagaExecutionControllerState) => state = snapshot
  }

  def getSagaExecutionControllerState(): SagaExecutionControllerState = {
    state match {
      case state@SagaExecutionControllerState(_, _, _, _, _) => return state
      case state => throw new Exception("Invalid SagaExecutionController state type for SagaExecutionControllerActor: " + persistenceId + ".A state ActorState.SagaExecutionControllerState type was expected, but " + state.toString + " was found.")
    }
  }

  def buildResponseEvent(request: MessageTypes.Request, requesterPath: ActorPath): Event = {
    if (validateState(request) == false) {
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidUserException(request.key.toString + ": A saga needs to be loaded, before it may be executed!"))
    }
    request match {
      case LoadSaga(entityId: Long, saga: Saga) =>
        return SagaLoaded(saga, requesterPath)
      case Execute(entityId: Long) =>
        return Executing()
      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case Execute(_) =>
        return getSagaExecutionControllerState().state == SagaExecutionControllerSMState.STATE_GO
      case _ => return true
    }
  }

  // I override this just so it compiles (since i need to override buildResponseEvent
  override def buildResponseEvent(request: MessageTypes.Request): Event = {
    throw new UnsupportedOperationException("buildResponseEvent(Request) of SagaExecutionController shouldnt have been called.")
  }

  override val receiveCommand: Receive = {
    case command: MessageTypes.Command =>
      sendPersistentResponse(buildResponseEvent(command, sender().path))
      command match {
        case Execute(_) =>
          execute()
        case _ =>
      }

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)

    case evt: MessageTypes.Event =>
    //ignore

    case value => throw new IllegalArgumentException(value.toString)
  }

  def persistForSelf(evt: Event): Unit = {
    persist(evt) { event =>
      updateState(event)
    }
  }

  def doForwardOperation(op: SagaOperation, currIndex: Int, forwardId: Long, numTries: Int): Unit = {
    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(connectionTimeout * (numTries + 1) seconds) //Exponential backoff waits 5, 6, 8, 12, 20 seconds
    val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    val finalUri: String = loadBalancerURI + op.pathForward + "?operationId=" + forwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)

    future.onComplete {
      case Success(res) =>
        if (op.forwardSuccessfulCondition(res))
          op.state = OperationState.SUCCESS_NO_ROLLBACK
        else
          op.state = OperationState.FAILURE

      case Failure(exception) =>
        if (exception.isInstanceOf[TimeoutException]) {
          if (numTries >= MAX_NUM_TIMEOUTS_ALLOWED)
            op.state = OperationState.FAILURE;
          else
            doForwardOperation(op, currIndex, forwardId, numTries + 1) //recurr to try again

        }
    }
  }

  def doBackwardOperation(op: SagaOperation, currIndex: Int, backwardId: Long, numTries: Int): Unit = {
    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(Math.min(connectionTimeout * (numTries + 1), maxConnectionTimeout) seconds) //Exponential backoff waits 5, 6, 8, 12, 20 seconds
    val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    val finalUri: String = loadBalancerURI + op.pathInvert + "?operationId=" + backwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)

    future.onComplete {
      case Success(res) =>
        op.state = OperationState.SUCCESS_ROLLEDBACK
      case Failure(exception) =>
        if (exception.isInstanceOf[TimeoutException]) {
          //recurr to try again // Using linear backoff to hopefully deal with failures
          doBackwardOperation(op, currIndex, backwardId, numTries + 1)
        }
    }

  }

  def execute(): Unit = {
    var currIndex = getSagaExecutionControllerState().concurrentOperationsIndex
    var state = getSagaExecutionControllerState().state

    if (state == SagaExecutionControllerSMState.STATE_PRE_EXEC || state == SagaExecutionControllerSMState.STATE_POST_EXEC_FAIL || state == SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC) {
      return
    }

    var opsAtIndex = getSagaExecutionControllerState().saga.dagOfOps.get(currIndex)
    var finished = false
    val forwardId: Long = getSagaExecutionControllerState().saga.forwardId
    val backwardId: Long = getSagaExecutionControllerState().saga.backwardId

    while (!finished) {
      opsAtIndex = getSagaExecutionControllerState().saga.dagOfOps.get(currIndex)

      if (state == SagaExecutionControllerSMState.STATE_GO) {
        for (op <- opsAtIndex.asScala) {
          if (op.state == OperationState.UNPERFORMED)
            doForwardOperation(op, currIndex, forwardId, 0)
        }

        while (opsAtIndex.asScala.exists(op => op.state == OperationState.UNPERFORMED)) {
          Thread.sleep(50)
        }

        if (opsAtIndex.asScala.forall(op => op.state == OperationState.SUCCESS_NO_ROLLBACK)) {
          persistForSelf(StepCompleted(currIndex))
          currIndex += 1
          if (currIndex == getSagaExecutionControllerState().saga.dagOfOps.asScala.length)
            finished = true
        } else { //If any of them are FAILURE
          persistForSelf(StepFailed())
          state = SagaExecutionControllerSMState.STATE_ROLLBACK
        }

      } else if (state == SagaExecutionControllerSMState.STATE_ROLLBACK) {
        for (op <- opsAtIndex.asScala) {
          if (op.state == OperationState.SUCCESS_NO_ROLLBACK) {
            doBackwardOperation(op, currIndex, backwardId, 0)
          } else if (op.state == OperationState.UNPERFORMED) {
            doForwardOperation(op, currIndex, forwardId, 0)
            while (op.state == OperationState.UNPERFORMED) {
              Thread.sleep(50)
            }
            if (op.state == OperationState.SUCCESS_NO_ROLLBACK) {
              doBackwardOperation(op, currIndex, backwardId, 0)
            }
          } else {
            op.state = OperationState.SUCCESS_ROLLEDBACK //if op completed but not success. aka the one that triggered rollback
          }
        }
        while (opsAtIndex.asScala.exists(op => op.state == OperationState.UNPERFORMED || op.state == OperationState.SUCCESS_NO_ROLLBACK)) {
          Thread.sleep(50)
        }
        persistForSelf(StepRollbackCompleted(currIndex))
        currIndex -= 1
        if (currIndex == -1)
          finished = true
      }
    }

    if (state == SagaExecutionControllerSMState.STATE_GO) {
      context.actorSelection(getSagaExecutionControllerState().requesterPath) ! SagaCompleted()
      persistForSelf(SagaCompleted())
    } else {
      context.actorSelection(getSagaExecutionControllerState().requesterPath) ! SagaAborted()
      persistForSelf(SagaAborted())
    }
  }

}
