package org.flocka.Services.Order

import akka.actor.{Props, _}
import akka.persistence.SnapshotOffer
import org.flocka.ServiceBasics.IdManager.InvalidIdException
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.{Configs, MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.Order.OrderServiceComs._
import org.flocka.Utils.PushOutHashmapQueueBuffer

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * OrderRepository props and custom exceptions for an order.
  */
object OrderRepository {
  def props(): Props = Props(new OrderRepository())
}

/**
  * Hold the current state of the order here.
  *
  * @param orderId matches the persistenceId and is the unique identifier for order and actor.
  * @param userId the user, by id connected to this order
  * @param paymentStatus identifies if the order is paid or not
  * @param items all items, with their id, attached to this order
  * @param active does this order still exist
  */
case class OrderState(orderId: Long,
                     userId: Long,
                     paymentStatus: Boolean,
                     items: mutable.ListBuffer[Long],
                      active: Boolean) {
  def updated(event: MessageTypes.Event): OrderState = event match {
    case OrderCreated(orderId, userId) =>
      copy(orderId = orderId, userId = userId, paymentStatus = false, mutable.ListBuffer.empty[Long], active = true)
    case OrderDeleted(orderId, true) =>
      copy(orderId = orderId, userId = userId, paymentStatus = paymentStatus, mutable.ListBuffer.empty[Long], active = false)
    case ItemAdded(orderId, itemId, true, operationId) =>
      copy(orderId = orderId, userId = userId, paymentStatus = paymentStatus, items += itemId, active = active)
    case ItemRemoved(orderId, itemId, true, operationId) =>
      copy(orderId = orderId, userId = userId, paymentStatus = paymentStatus, items -= itemId, active = active)
    case OrderCheckedOut(orderId, true) =>
      copy(orderId = orderId, userId = userId, paymentStatus = true, items = items, active = active)
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

/**
  * Holds all orders
  */
case class OrderRepositoryState(orders: mutable.Map[Long, OrderState], currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations: PushOutHashmapQueueBuffer[Long, Event] = currentOperations

  def updated(event: MessageTypes.Event): OrderRepositoryState = event match {
    case OrderCreated(orderId, userId) =>
      copy(orders += orderId -> new OrderState(-1, -1, false, mutable.ListBuffer.empty[Long], false).updated(event))
    case OrderDeleted(orderId, true) =>
      orders.get(orderId).get.updated(event)
      copy(orders -= orderId)
    case ItemAdded(orderId, itemId, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(orders += orderId -> orders.get(orderId).get.updated(event))
    case ItemRemoved(orderId, itemId, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(orders += orderId -> orders.get(orderId).get.updated(event))
    case OrderCheckedOut(orderId, true) =>
      copy(orders += orderId -> orders.get(orderId).get.updated(event))

      /**
      Ignore these events, they have no state changes
       */
    case ItemRemoved(_ ,_ , false, _) | ItemAdded(_ ,_ , false, _) | OrderCheckedOut(_ , false) => this
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for OrderActor.")
  }
}

/**
  * Actor storing the current state of a order.
  * All valid commands/ queries for orders are resolved here and then send back to the requesting actor
  */
class OrderRepository extends PersistentActorBase {
  override var state: PersistentActorState = new OrderRepositoryState(mutable.Map.empty[Long, OrderState], new PushOutHashmapQueueBuffer[Long, Event](500))

  val passivateTimeout: FiniteDuration = Configs.conf.getInt("user.passivate-timeout") seconds
  val snapShotInterval: Int = Configs.conf.getInt("user.snapshot-interval")
  // Since we have millions of order, we should passivate quickly
  context.setReceiveTimeout(passivateTimeout)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case SnapshotOffer(_, snapshot: OrderRepositoryState) => state = snapshot
  }

  def getOrderRepository(): OrderRepositoryState = {
    state match {
      case state@ OrderRepositoryState(_ , _) => return state
      case state => throw new Exception("Invalid OrderRepository state type for order-repository: " + persistenceId + ". A state ActorState.OrderRepositoryState type was expected, but " + state.getClass.toString + " was found.")
    }
  }

  def getOrderState(orderId: Long): Option[OrderState] = {
    getOrderRepository().orders.get(orderId)
  }

  def buildResponseEvent(request: MessageTypes.Request): Event = {
    if (validateState(request) == false) {
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidOrderException(request.key.toString))
    }

    request match {
      case CreateOrder(orderId, userId) => return OrderCreated(orderId, userId)

      case DeleteOrder(orderId) => return OrderDeleted(orderId, true)

      case AddItem(orderId, itemId, operationId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new Exception("Order does not exist."))
        val success = !orderState.paymentStatus
        return ItemAdded(orderId, itemId, success, operationId)

      case RemoveItem(orderId, itemId, operationId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new Exception("Order does not exist."))
        val success = !orderState.paymentStatus && orderState.items.contains(itemId)
        return ItemRemoved(orderId, itemId, success, operationId)

      case FindOrder(orderId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new Exception("Order does not exist."))
        return OrderFound(orderId, orderState.userId, orderState.paymentStatus, orderState.items.toList)

      case CkeckoutOrder(orderId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new Exception("Order does not exist."))
        val success = !orderState.paymentStatus
        return OrderCheckedOut(orderId, success)

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case CreateOrder(orderId, userId) =>
        if (getOrderState(orderId).isDefined) {
          sender() ! akka.actor.Status.Failure(InvalidIdException(orderId.toString))
          return false
        }
        else
        //ToDO: do we want to verify the userId?
          return true
      case _ => return getOrderState(request.key).getOrElse(return false).active
    }
  }
}