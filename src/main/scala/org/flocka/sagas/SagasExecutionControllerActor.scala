package org.flocka.sagas

import akka.actor.{ActorLogging, ActorSystem, Props}
import akka.persistence.{RecoveryCompleted, SnapshotOffer}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.Utils.PushOutHashmapQueueBuffer
import org.flocka.sagas.SagaExecutionControllerComs._
import scala.concurrent.{ExecutionContext, Future}

object SagasExecutionControllerActor {
  val MAX_NUM_TRIES = 5

  def props(): Props = Props(new SagasExecutionControllerActor())
}

case class SagaExecutionControllerState(saga: Saga) extends PersistentActorState {
  override var doneOperations = new PushOutHashmapQueueBuffer[Long, Event](0)

  def updated(event: Event): SagaExecutionControllerState = event match {
    case SagaCreated(saga: Saga) =>
      saga.currentState = SagaState.PENDING
      copy(saga)
    case SagaCompleted() =>
      saga.currentState = SagaState.SUCCESS
      copy(saga)
    case SagaAborted() =>
      saga.currentState = SagaState.FAILURE
      copy(saga)
  }
}

class SagasExecutionControllerActor() extends PersistentActorBase with ActorLogging {
  override var state: PersistentActorState = new SagaExecutionControllerState(new Saga(-1))

  val config : Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")
  val loadBalancerURI = config.getString("clustering.loadbalancer.uri")

  context.setReceiveTimeout(passivateTimeout)
  implicit val system: ActorSystem = context.system
  implicit val executor: ExecutionContext = context.dispatcher

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => {updateState(evt)}
    case RecoveryCompleted =>
      val saga: Saga = getSagaExecutionControllerState.saga
      self ! ExecuteSaga(saga)
    case SnapshotOffer(_, snapshot: SagaExecutionControllerState) => state = snapshot
  }

  def getSagaExecutionControllerState: SagaExecutionControllerState = {
    state match {
      case state@SagaExecutionControllerState(_) => return state
      case state => throw new Exception("Invalid SagaExecutionController state type for SagaExecutionControllerActor: " + persistenceId + ".A state ActorState.SagaExecutionControllerState type was expected, but " + state.toString + " was found.")
    }
  }

  def buildResponseEvent(request: MessageTypes.Request): Event = {
    request match {
      case ExecuteSaga(saga: Saga) =>
        persistForSelf(SagaCreated(saga))
        return saga.execute(self)
      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    return true
  }

  def persistForSelf(evt: Event): Unit = {
    persist(evt) { event =>
      updateState(event)
    }
  }
}