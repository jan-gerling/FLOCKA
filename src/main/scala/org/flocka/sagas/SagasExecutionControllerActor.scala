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
import org.flocka.ServiceBasics.{Configs, MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.ServiceBasics.MessageTypes.{Command, Event, Query}
import org.flocka.Services.User.{UserRepositoryState, UserState}
import org.flocka.sagas.SagaExecutionControllerComs.{ExecuteNextStep, RequestExecution, RollbackStarted, SagaAborted, SagaCompleted, SagaStarted, StepCompleted, StepStarted}

import scala.collection.mutable
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SagasExecutionControllerActor {

  final val STATE_GO: Int = 0
  final val STATE_ROLLBACK: Int = 1

  val loadBalancerURI = ConfigFactory.load().getString("loadbalancer.uri")

  def props(): Props = Props(new SagasExecutionControllerActor())
}

case class SECState(saga: Saga, concurrentOperationsIndex: Int, state: Int, entityId: Long, requester: ActorRef, finished: Boolean) extends PersistentActorState {

  def updated(event: Event): SECState = event match {
    case SagaStarted(saga: Saga, cmdRequester: ActorRef) =>
      copy(saga, 0, SagasExecutionControllerActor.STATE_GO, entityId, cmdRequester, false)
    case StepStarted(index: Int) =>
      copy(saga, concurrentOperationsIndex, state, entityId, requester, finished)
    case StepCompleted(newStep: Int) =>
      copy(saga, newStep, state, entityId, requester, finished)
    case SagaCompleted() =>
      copy(saga, concurrentOperationsIndex, state, entityId, requester, true)
    case RollbackStarted() =>
      copy(saga, concurrentOperationsIndex, SagasExecutionControllerActor.STATE_ROLLBACK, entityId, requester, finished)
  }

}

class SagasExecutionControllerActor() extends PersistentActorBase with ActorLogging {
  override var state: PersistentActorState = new SECState(new Saga(), 0, SagasExecutionControllerActor.STATE_GO, 0L, self, false) // For some reason i cant use _, if someone knows a fix, please tell
  val passivateTimeout: FiniteDuration = Configs.conf.getInt("sec.passivate-timeout") seconds
  val snapShotInterval: Int = Configs.conf.getInt("sec.snapshot-interval")
  context.setReceiveTimeout(passivateTimeout)
  val system = context.system
  val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(5 seconds)
  val connectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)


  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case RecoveryCompleted =>
      doStep()
    case SnapshotOffer(_, snapshot: SECState) => state = snapshot
  }

  def getSECState(): SECState = {
    state match {
      case state@SECState(_, _, _, _, _, _) => return state
      case state => throw new Exception("Invalid SEC state type for SagaExecutionControllerActor: " + persistenceId + ".A state ActorState.SECState type was expected, but " + state.toString + " was found.")
    }
  }

  def buildResponseEvent(request: MessageTypes.Request, requester: ActorRef): Event = {
    //if (validateState(request) == false) {
    //  sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidUserException(request.key.toString))
    //}
    request match {
      case RequestExecution(entityId: Long, saga: Saga) =>
        return SagaStarted(saga, requester)
      case ExecuteNextStep(_) =>
        doStep()
        return StepStarted(getSECState().concurrentOperationsIndex)

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case _ => return true
    }
  }

  // I override this just so it compiles (since i need to override buildResponseEvent
  override def buildResponseEvent(request: MessageTypes.Request): Event = return SagaCompleted()

  override val receiveCommand: Receive = {
    case command: MessageTypes.Command => sendPersistentResponse(buildResponseEvent(command, sender()))

    case query: MessageTypes.Query => sendResponse(buildResponseEvent(query))

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)

    case value => throw new IllegalArgumentException(value.toString)
  }

  def doForwardOperation(op: SagaOperation, currIndex: Int): Unit = {
    val finalUri: String = SagasExecutionControllerActor.loadBalancerURI + "/" + op.pathForward + "?operationId=" + op.id
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)
    future.onComplete {
      case Success(res) =>
        if (op.forwardSuccessfulCondition(res)) {

          op.completed = true
          op.success = true

          if (getSECState().saga.dagOfOps(currIndex).forall(op => op.success)) { // If all concurrent ops int this step are finished
            if (currIndex == getSECState().saga.dagOfOps.length - 1) { // If this was the last step
              //We are done, successfully
              if(!getSECState().finished) {
                self ! SagaCompleted
                getSECState().requester ! SagaCompleted
              }
            } else {
              self ! StepCompleted(currIndex + 1)
              self ! ExecuteNextStep(getSECState().entityId)
            }
          }
        } else {
          op.completed = true
          op.success = false
          while(!getSECState().saga.dagOfOps(currIndex).forall(op => op.completed)) //wait for others to complete

          //Need to begin abort
          self ! RollbackStarted
        }
      case Failure(exception) =>
        if (exception.toString.contains("timeout")) {
          //recurr to try again
          doForwardOperation(op, currIndex)
        }
    }
  }

  def doBackwardOperation(op: SagaOperation, currIndex: Int): Unit = {
    val finalUri: String = SagasExecutionControllerActor.loadBalancerURI + "/" + op.pathInvert + "?operationId=" + op.backwardId
    val future: Future[HttpResponse] = Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = finalUri), settings = connectionPoolSettings)
    future.onComplete {
      case Success(res) =>
        op.invertCompleted = true
        if (getSECState().saga.dagOfOps(currIndex).forall(op => (op.completed == true && op.success == false && op.invertCompleted == false) || op.invertCompleted == true)){
          if(currIndex == 0) {
            self ! SagaAborted
            getSECState().requester ! SagaAborted
          }else {
            self ! StepCompleted(currIndex - 1)
            self ! ExecuteNextStep(getSECState().entityId)
          }
        }
      case Failure(exception) =>
        if (exception.toString.contains("timeout")) {
          //recurr to try again
          doBackwardOperation(op, currIndex)
        }
    }



  }

  def doStep(): Unit = {
    val currIndex = getSECState().concurrentOperationsIndex
    val opsAtIndex = getSECState().saga.dagOfOps(currIndex)
    if (getSECState().state == SagasExecutionControllerActor.STATE_GO) {
      for (op <- opsAtIndex)
        if (!op.completed)
          doForwardOperation(op, currIndex)
    } else if (getSECState().state == SagasExecutionControllerActor.STATE_ROLLBACK) {
      for (op <- opsAtIndex)
        if (op.completed && op.success && !op.invertCompleted)
          doBackwardOperation(op, currIndex)
    }
  }


}
