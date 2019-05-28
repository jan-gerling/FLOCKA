package org.flocka.sagas

import akka.actor.{ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

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

object SagasExecutionControllerActor {
  final val STATE_PRE_EXEC: Int = 0
  final val STATE_GO: Int = 1
  final val STATE_ROLLBACK: Int = 2
  final val STATE_POST_EXEC_SUCC: Int = 3
  final val STATE_POST_EXEC_FAIL: Int = 4


  def props(): Props = Props(new SagasExecutionControllerActor())
}

case class SECState(saga: Saga, concurrentOperationsIndex: Int, state: Int, entityId: Long, requester: ActorRef) extends PersistentActorState {

  override var doneOperations = new PushOutHashmapQueueBuffer[Long, Event](0)

  def updated(event: Event): SECState = event match {
    case SagaLoaded(saga: Saga, cmdRequester: ActorRef) =>
      copy(saga, 0, SagasExecutionControllerActor.STATE_GO, entityId, cmdRequester)
    case Executing() =>
      copy(saga, concurrentOperationsIndex, state, entityId, requester)
    case StepRollbackCompleted(step: Int) =>
      copy(saga, step -1, state, entityId, requester)
    case StepCompleted(step: Int) =>
      copy(saga, step + 1, state, entityId, requester)
    case SagaCompleted() =>
      copy(saga, concurrentOperationsIndex, SagasExecutionControllerActor.STATE_POST_EXEC_SUCC, entityId, requester)
    case RollbackStarted() =>
      copy(saga, concurrentOperationsIndex, SagasExecutionControllerActor.STATE_ROLLBACK, entityId, requester)
    case SagaAborted() =>
      copy(saga, concurrentOperationsIndex, SagasExecutionControllerActor.STATE_POST_EXEC_FAIL, entityId, requester)
  }

}

class SagasExecutionControllerActor() extends PersistentActorBase with ActorLogging {
  override var state: PersistentActorState = new SECState(new Saga(), 0, SagasExecutionControllerActor.STATE_PRE_EXEC, 0L, self) // For some reason i cant use _, if someone knows a fix, please tell
  val config : Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")
  val loadBalancerURI = config.getString("clustering.loadbalancer.uri")

  context.setReceiveTimeout(passivateTimeout)
  val system = context.system
  val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(5 seconds)
  val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)


  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => {updateState(evt)}
    case RecoveryCompleted =>
      execute()
    case SnapshotOffer(_, snapshot: SECState) => state = snapshot
  }

  def getSECState(): SECState = {
    state match {
      case state@SECState(_, _, _, _, _) => return state
      case state => throw new Exception("Invalid SEC state type for SagaExecutionControllerActor: " + persistenceId + ".A state ActorState.SECState type was expected, but " + state.toString + " was found.")
    }
  }

  def buildResponseEvent(request: MessageTypes.Request, requester: ActorRef): Event = {
    //if (validateState(request) == false) {
    //  sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidUserException(request.key.toString))
    //}
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
      case _ => return true
    }
  }

  // I override this just so it compiles (since i need to override buildResponseEvent
  override def buildResponseEvent(request: MessageTypes.Request): Event = {println("THIS SHOULDNT HAVE BEEN CALLED");return SagaCompleted()}

  override val receiveCommand: Receive = {
    case command: MessageTypes.Command =>
      sendPersistentResponse(buildResponseEvent(command, sender()))
      command match {
        case Execute(_) =>
          execute()
        case _ =>

      }

    case query: MessageTypes.Query => sendResponse(buildResponseEvent(query))

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

  def doForwardOperation(op: SagaOperation, currIndex: Int): Unit = {
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
          //recurr to try again
          doForwardOperation(op, currIndex)
        }
    }
  }

  def doBackwardOperation(op: SagaOperation, currIndex: Int): Unit = {
    val finalUri: String = loadBalancerURI + op.pathInvert + "?operationId=" + op.backwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)
    future.onComplete {
      case Success(res) =>
        op.invertCompleted = true
      case Failure(exception) =>
        if (exception.toString.contains("timeout")) {
          //recurr to try again
          doBackwardOperation(op, currIndex)
        }
    }

  }

  def execute(): Unit = {
    var currIndex = getSECState().concurrentOperationsIndex
    var state = getSECState().state

    if (state == SagasExecutionControllerActor.STATE_PRE_EXEC || state ==  SagasExecutionControllerActor.STATE_POST_EXEC_FAIL || state == SagasExecutionControllerActor.STATE_POST_EXEC_SUCC ){
      return
    }
    var opsAtIndex = getSECState().saga.dagOfOps(currIndex)

    var finished = false

    while (!finished) {
      opsAtIndex = getSECState().saga.dagOfOps(currIndex)

      if (state == SagasExecutionControllerActor.STATE_GO) {
        for (op <- opsAtIndex) {
          if (!op.completed)
            doForwardOperation(op, currIndex)
        }
        while(opsAtIndex.exists(op => !op.completed)) {Thread.sleep(50)}
        if(opsAtIndex.forall(op =>  op.success)) {
          persistForSelf(StepCompleted(currIndex))
          currIndex += 1
          if(currIndex == getSECState().saga.dagOfOps.length)
            finished = true
        } else {
          persistForSelf(RollbackStarted())
          state =  SagasExecutionControllerActor.STATE_ROLLBACK
        }
      } else if (state == SagasExecutionControllerActor.STATE_ROLLBACK) {

        for (op <- opsAtIndex) {
          if (op.completed && op.success && !op.invertCompleted) {
            doBackwardOperation(op, currIndex)
          }else if(!op.completed) {
            doForwardOperation(op, currIndex)
            while(!op.completed){Thread.sleep(50)}
            if(op.success) {
              doBackwardOperation(op, currIndex)
            }
          }else {
            op.invertCompleted = true //if op completed but not success. aka the one that triggered rollback
          }
        }
        while(opsAtIndex.exists(op => !op.invertCompleted)) {Thread.sleep(50)}
        persistForSelf(StepRollbackCompleted(currIndex))
        currIndex -= 1
        if(currIndex == -1)
          finished = true
      }
      while(opsAtIndex.exists(op => !op.completed)) {Thread.sleep(50)}

    }
    if(state == SagasExecutionControllerActor.STATE_GO) {
      getSECState().requester ! SagaCompleted()
      persistForSelf(SagaCompleted())
    }else {
      getSECState().requester ! SagaAborted()
      persistForSelf(SagaAborted())
    }
  }

}
