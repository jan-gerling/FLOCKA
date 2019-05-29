package org.flocka.Services.Stock

import akka.actor.Props
import akka.persistence.SnapshotOffer
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.PersistentActorBase.InvalidStockException
import org.flocka.ServiceBasics._
import org.flocka.Services.Stock.StockServiceComs._
import org.flocka.Utils.PushOutHashmapQueueBuffer

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * StockActor props and custom exceptions for a StockActor
  */
object StockRepository {
  def props(): Props = Props(new StockRepository())
}

/**
  * Hold the current state of the stock item here
  *
  * @param itemId       matches persistenceId and is the unique identifier for item and actor.
  * @param availability is the currently available amaount of this stock item
  */

case class StockState(itemId: Long,
                      availability: Long) {
  def updated(event: MessageTypes.Event): StockState = event match {
    case ItemCreated(itemId) =>
      copy(itemId = itemId, availability = 0)
    case AvailabilityIncreased(itemId, amount, true, _) =>
      copy(itemId = itemId, availability = availability + amount)
    case AvailabilityDecreased(itemId, amount, true, _) =>
      copy(itemId = itemId, availability = availability - amount)
    case _ => throw new IllegalArgumentException(event.toString + " is not a valid event for the StockActor.")
  }
}

case class StockRepositoryState(stockItems: mutable.Map[Long, StockState], currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations: PushOutHashmapQueueBuffer[Long, Event] = currentOperations

  override def updated(event: MessageTypes.Event): StockRepositoryState = event match {
    case ItemCreated(itemId) =>
      copy(stockItems += itemId -> new StockState(-1, -1).updated(event))
    case AvailabilityIncreased(itemId, _, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(stockItems += itemId -> stockItems.get(itemId).get.updated(event))
    case AvailabilityDecreased(itemId, _, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(stockItems += itemId -> stockItems.get(itemId).get.updated(event))
    case _ => throw new IllegalArgumentException(event.toString + " is not a valid event for StockActor.")
  }
}

/**
  * Actor storing the current state of a stock item
  * All valid commands / queries for stock items are resolved here and then sent back to the requesitng actor (supposed to be StockService via StockActorSupervisor
  */
class StockRepository extends PersistentActorBase {
  override var state: PersistentActorState = new StockRepositoryState(mutable.Map.empty[Long, StockState], new PushOutHashmapQueueBuffer[Long, Event](500))

  val config: Config = ConfigFactory.load("stock-service.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

  // Since we have millions of stock items, we should passivate quickly
  context.setReceiveTimeout(passivateTimeout)

  override def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  override def receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case SnapshotOffer(_, snapshot: StockRepositoryState) => state = snapshot
  }

  def getStockRepository(): StockRepositoryState = {
    state match {
      case state@StockRepositoryState(_, _) => state
      case state => throw new Exception("Invalid stock-repository state type for stock-repository: " + persistenceId +
        ".A state ActorState.StockRepository type was expected, but " + state.toString + " was found.")
    }
  }

  def getStockState(itemId: Long): Option[StockState] =
    getStockRepository().stockItems.get(itemId)

  override def buildResponseEvent(request: MessageTypes.Request): MessageTypes.Event = {
    request match {
      case CreateItem(itemId) =>
        return ItemCreated(itemId)
      case IncreaseAvailability(itemId, amount, operationId) =>
        return AvailabilityIncreased(itemId, amount, true, operationId)
      case DecreaseAvailability(itemId, amount, operationId) =>
        return AvailabilityDecreased(itemId, amount, true, operationId)
      case GetAvailability(itemId) =>
        return AvailabilityGot(itemId, getStockState(itemId).get.availability)
      case _ =>
        throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case CreateItem(itemId) =>
        if (getStockState(itemId).isDefined)
          throw new InvalidStockException("Stock of item itd " + itemId + " Already exists.")
        else
          return true
      case DecreaseAvailability(itemId, amount, _) =>
        val stockState = getStockState(itemId).getOrElse(throw new InvalidStockException("Stock does not exist."))
        return stockState.availability >= amount
      case _ => getStockState(request.key).isDefined
    }
  }
}
