package org.flocka.sagas

import akka.actor.{ActorPath, ActorRef, ActorSystem, Cancellable, Identify, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}

import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.sagas.SagaComs._

import scala.concurrent.ExecutionContext

object SagaStorage {
  val MAX_NUM_TRIES = 3

  def props(): Props = Props(new SagaStorage())
}

case class SagaExecutionControllerState(saga: Saga, requesterPath: ActorRef)  {
  def updated(event: Event): SagaExecutionControllerState = event match {
    case SagaStored(saga: Saga, requesterPath: ActorRef) =>
      copy(saga, requesterPath)
    case SagaCompleted(saga: Saga) =>
      copy(saga, requesterPath)
    case SagaFailed(saga: Saga) =>
      copy(saga, requesterPath)
  }
}

class SagaStorage extends PersistentActor {
  override def persistenceId = self.path.name

  var state: SagaExecutionControllerState = new SagaExecutionControllerState(new Saga(-1), self)
  val config : Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

  val tickInitialDelay = config.getInt("recovery.tick-initial-delay") //When to check in on saga
  val tickInterval = config.getInt("recovery.tick-interval") // make sure its running every x millis
  var tick : Cancellable = _

  context.setReceiveTimeout(passivateTimeout)
  implicit val system: ActorSystem = context.system
  implicit val executor: ExecutionContext = context.dispatcher

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => {updateState(evt)}
    case SnapshotOffer(_, snapshot: SagaExecutionControllerState) => state = snapshot
  }

  val receiveCommand: Receive = {
    case ExecuteSaga(saga: Saga, requesterPath: ActorRef) =>
      println(requesterPath + " wants to execute: " + saga)
      persist(SagaStored(saga, requesterPath)) { eventStore =>
        updateState(eventStore)
        //register tick on execution requested
        tick = context.system.scheduler.schedule(tickInitialDelay millis, tickInterval millis, self, "tick")
        executeAndPersistSaga(saga: Saga, requesterPath)
      }
    case "tick" =>
      //Correctness guaranteed by using persist instead of persistAsync (this way, only one command is processed at a
      //time. Subsequent ticks throw IllegalAccessException
      executeAndPersistSaga(state.saga, state.requesterPath)
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case message@ _ => throw new IllegalArgumentException(message.toString)
  }

  def executeAndPersistSaga(saga: Saga, requesterPath: ActorRef): Unit = {
    val eventExecution: Event = saga.execute()
    persist(eventExecution) { eventExecution =>
      updateState(eventExecution)
      println(self.path)
      println(requesterPath)
      requesterPath ! eventExecution
      tick.cancel() //on execution concluded, cancel tick
    }
  }
}