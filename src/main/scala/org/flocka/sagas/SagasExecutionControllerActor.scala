package org.flocka.sagas

import akka.actor.{ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout}
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
import org.flocka.sagas.SagaExecutionControllerComs.{Execute, Executing, LoadSaga, RollbackStarted, SagaAborted, SagaCompleted, SagaLoaded, StepCompleted, StepRollbackCompleted}

import scala.collection.mutable
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


//Possible State Machine states
object SagaExecutionControllerSMState extends Enumeration {
  val STATE_PRE_EXEC, STATE_GO, STATE_ROLLBACK, STATE_POST_EXEC_SUCC, STATE_POST_EXEC_FAIL = Value
}

object SagasExecutionControllerActor {
  val MAX_NUM_TRIES = 5


  def props(): Props = Props(new SagasExecutionControllerActor())
}

case class SagaExecutionControllerState(saga: Saga, concurrentOperationsIndex: Int, state: SagaExecutionControllerSMState.Value, entityId: Long, requester: ActorRef) extends PersistentActorState {

  override var doneOperations = new PushOutHashmapQueueBuffer[Long, Event](0)

  def updated(event: Event): SagaExecutionControllerState = event match {
    case SagaLoaded(saga: Saga, cmdRequester: ActorRef) =>
      copy(saga, 0, SagaExecutionControllerSMState.STATE_GO, entityId, cmdRequester)
    case Executing() =>
      copy(saga, concurrentOperationsIndex, state, entityId, requester)
    case StepRollbackCompleted(step: Int) =>
      copy(saga, step -1, state, entityId, requester)
    case StepCompleted(step: Int) =>
      copy(saga, step + 1, state, entityId, requester)
    case SagaCompleted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC, entityId, requester)
    case RollbackStarted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_ROLLBACK, entityId, requester)
    case SagaAborted() =>
      copy(saga, concurrentOperationsIndex, SagaExecutionControllerSMState.STATE_POST_EXEC_FAIL, entityId, requester)
  }

}

class SagasExecutionControllerActor() extends PersistentActorBase with ActorLogging {
  override var state: PersistentActorState = new SagaExecutionControllerState(new Saga(), 0, SagaExecutionControllerSMState.STATE_PRE_EXEC, 0L, self) // For some reason i cant use _, if someone knows a fix, please tell
  val config : Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")
  val loadBalancerURI = config.getString("clustering.loadbalancer.uri")

  context.setReceiveTimeout(passivateTimeout)
  val system = context.system



  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => {updateState(evt)}
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

  def buildResponseEvent(request: MessageTypes.Request, requester: ActorRef): Event = {
    if (validateState(request) == false) {
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidUserException(request.key.toString + ": A saga needs to be loaded, before it may be executed!"))
    }
    request match {
      case LoadSaga(entityId: Long, saga: Saga) =>
        return SagaLoaded(saga, requester)
      case Execute(entityId: Long) =>
        return Executing()
      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case Execute(_) =>
        if(getSagaExecutionControllerState().state == SagaExecutionControllerSMState.STATE_GO)
          return true
        else
          return false
      case _ => return true
    }
  }

  // I override this just so it compiles (since i need to override buildResponseEvent
  override def buildResponseEvent(request: MessageTypes.Request): Event = {throw new UnsupportedOperationException("buildResponseEvent(Request) of SagaExecutionController shouldnt have been called.")}

  override val receiveCommand: Receive = {
    case command: MessageTypes.Command =>
      sendPersistentResponse(buildResponseEvent(command, sender()))
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

  def doForwardOperation(op: SagaOperation, currIndex: Int, numTries: Int): Unit = {
    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(5 + Math.pow(2, numTries) - 1   seconds) //Exponential backoff waits 5, 6, 8, 12, 20 seconds
    val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    val finalUri: String = loadBalancerURI  + op.pathForward + "?operationId=" + op.id
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)
    future.onComplete {
      case Success(res) =>
        if (op.forwardSuccessfulCondition(res)) {
          op.completed = true
          op.success = true
        } else {
          op.completed = true
          op.success = false
          //Need to begin abort
        }
      case Failure(exception) =>
        if (exception.toString.contains("timeout")) {
          if(numTries >= SagasExecutionControllerActor.MAX_NUM_TRIES ){
            op.completed = true
            op.success = false // This will cause an abort.
          } else {
            doForwardOperation(op, currIndex, numTries + 1) //recurr to try again
          }
        }
    }
  }

  def doBackwardOperation(op: SagaOperation, currIndex: Int, numTries: Int): Unit = {
    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(5 + Math.min(Math.pow(2, numTries) - 1, 115)   seconds) //Exponential backoff waits 5, 6, 8, 12, 20, ... 120 seconds
    val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)
    val finalUri: String = loadBalancerURI + op.pathInvert + "?operationId=" + op.backwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)
    future.onComplete {
      case Success(res) =>
        op.invertCompleted = true
      case Failure(exception) =>
        if (exception.toString.contains("timeout")) {
          //recurr to try again // Using exponential backoff to hopefully deal with failures
          doBackwardOperation(op, currIndex, numTries + 1)
        }
    }

  }

  def execute(): Unit = {
    var currIndex = getSagaExecutionControllerState().concurrentOperationsIndex
    var state = getSagaExecutionControllerState().state

    if (state == SagaExecutionControllerSMState.STATE_PRE_EXEC || state ==  SagaExecutionControllerSMState.STATE_POST_EXEC_FAIL || state == SagaExecutionControllerSMState.STATE_POST_EXEC_SUCC ){
      return
    }
    var opsAtIndex = getSagaExecutionControllerState().saga.dagOfOps.get(currIndex)

    var finished = false

    while (!finished) {
      opsAtIndex = getSagaExecutionControllerState().saga.dagOfOps.get(currIndex)

      if (state == SagaExecutionControllerSMState.STATE_GO) {
        for (op <- opsAtIndex.asScala) {
          if (!op.completed)
            doForwardOperation(op, currIndex,0)
        }
        while(opsAtIndex.asScala.exists(op => !op.completed)) {Thread.sleep(50)}
        if(opsAtIndex.asScala.forall(op =>  op.success)) {
          persistForSelf(StepCompleted(currIndex))
          currIndex += 1
          if(currIndex == getSagaExecutionControllerState().saga.dagOfOps.asScala.length)
            finished = true
        } else {
          persistForSelf(RollbackStarted())
          state =  SagaExecutionControllerSMState.STATE_ROLLBACK
        }
      } else if (state == SagaExecutionControllerSMState.STATE_ROLLBACK) {

        for (op <- opsAtIndex.asScala) {
          if (op.completed && op.success && !op.invertCompleted) {
            doBackwardOperation(op, currIndex,0)
          }else if(!op.completed) {
            doForwardOperation(op, currIndex,0)
            while(!op.completed){Thread.sleep(50)}
            if(op.success) {
              doBackwardOperation(op, currIndex,0)
            }
          }else {
            op.invertCompleted = true //if op completed but not success. aka the one that triggered rollback
          }
        }
        while(opsAtIndex.asScala.exists(op => !op.invertCompleted)) {Thread.sleep(50)}
        persistForSelf(StepRollbackCompleted(currIndex))
        currIndex -= 1
        if(currIndex == -1)
          finished = true
      }
      while(opsAtIndex.asScala.exists(op => !op.completed)) {Thread.sleep(50)}

    }
    if(state == SagaExecutionControllerSMState.STATE_GO) {
      getSagaExecutionControllerState().requester ! SagaCompleted()
      persistForSelf(SagaCompleted())
    }else {
      getSagaExecutionControllerState().requester ! SagaAborted()
      persistForSelf(SagaAborted())
    }
  }

}
