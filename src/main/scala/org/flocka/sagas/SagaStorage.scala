package org.flocka.sagas

import akka.actor.{ActorPath, ActorSelection, ActorSystem, Cancellable, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}

import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.sagas.SagaComs._

import scala.concurrent.ExecutionContext

object SagaStorage {

  def props(): Props = Props(new SagaStorage())
}

case class SagaExecutionControllerState(saga: Saga, requesterPath: ActorPath)  {
  def updated(event: Event): SagaExecutionControllerState = event match {
    case SagaStored(saga: Saga, requesterPath: ActorPath) =>
      copy(saga, requesterPath)
    case SagaCompleted(saga: Saga) =>
      copy(saga, requesterPath)
    case SagaFailed(saga: Saga) =>
      copy(saga, requesterPath)
  }
}

class SagaStorage extends PersistentActor {
  override def persistenceId = self.path.name

  var state: SagaExecutionControllerState = new SagaExecutionControllerState(new Saga(-1), self.path)
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
    case ExecuteSaga(saga: Saga) =>
      val requesterPath: ActorPath = sender().path
      persist(SagaStored(saga, requesterPath)) { eventStore =>
        updateState(eventStore)
        //register tick on execution requested
        tick = context.system.scheduler.schedule(tickInitialDelay millis, tickInterval millis, self, "tick")
        executeAndPersistSaga(saga: Saga)
      }
    case "tick" =>
      //Correctness guaranteed by using persist instead of persistAsync (this way, only one command is processed at a
      //time. Subsequent ticks throw IllegalAccessException
      executeAndPersistSaga(state.saga)
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case message@ _ => throw new IllegalArgumentException(message.toString)
  }

  def executeAndPersistSaga(saga: Saga): Unit = {
    val eventExecution: Event = saga.execute()
    persist(eventExecution) { eventExecution =>
      updateState(eventExecution)
      context.actorSelection(state.requesterPath) ! eventExecution
      tick.cancel() //on execution concluded, cancel tick
    }
  }
}