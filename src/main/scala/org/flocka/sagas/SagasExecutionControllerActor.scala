package org.flocka.sagas

import akka.actor.{ActorSystem, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}
import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.sagas.SagaExecutionControllerComs._

import scala.concurrent.ExecutionContext

object SagasExecutionControllerActor {
  val MAX_NUM_TRIES = 5

  def props(): Props = Props(new SagasExecutionControllerActor())
}

case class SagaExecutionControllerState(saga: Saga)  {
  def updated(event: Event): SagaExecutionControllerState = event match {
    case SagaStored(saga: Saga) =>
      copy(saga)
    case SagaCompleted(saga: Saga) =>
      copy(saga)
    case SagaFailed(saga: Saga) =>
      copy(saga)
  }
}

class SagasExecutionControllerActor extends PersistentActor {
  override def persistenceId = self.path.name

  var state: SagaExecutionControllerState = new SagaExecutionControllerState(new Saga(-1))
  val config : Config = ConfigFactory.load("saga-execution-controller.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

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
      persist(SagaStored(saga)) { eventStore =>
        updateState(eventStore)
        val eventExecution: Event = saga.execute()
        persist(eventExecution) { eventExecution =>
          updateState(eventExecution)
          sender() ! eventExecution
          context.parent ! Passivate(stopMessage = PoisonPill)
        }
      }
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }
}