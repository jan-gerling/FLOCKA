package org.flocka.Services.Order

import java.net.URI

import akka.actor.{Props, _}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.persistence.SnapshotOffer
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.IdResolver.InvalidIdException
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.{CommandHandler, MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.Order.OrderServiceComs._
import org.flocka.Utils.{HttpHelper, PushOutHashmapQueueBuffer}
import org.flocka.sagas.SagaComs.{ExecuteSaga, SagaCompleted, SagaFailed}
import org.flocka.sagas.{Saga, SagaOperation}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * OrderRepository props and custom exceptions for an order.
  */
object OrderRepository {
  def props(): Props = Props(new OrderRepository())
}

/**
  * Hold the current state of the order here.
  *
  * @param orderId       matches the persistenceId and is the unique identifier for order and actor.
  * @param userId        the user, by id connected to this order
  * @param paymentStatus identifies if the order is paid or not
  * @param items         all items, with their id, attached to this order
  * @param active        does this order still exist
  */
case class OrderState(orderId: Long,
                      userId: Long,
                      paymentStatus: Boolean,
                      items: mutable.ListBuffer[(Long, Long)],
                      active: Boolean) {
  def updated(event: MessageTypes.Event): OrderState = event match {
    case OrderCreated(orderId, userId) =>
      copy(orderId = orderId, userId = userId, paymentStatus = false, mutable.ListBuffer.empty[(Long, Long)], active = true)
    case OrderDeleted(orderId, true) =>
      copy(orderId = orderId, userId = userId, paymentStatus = paymentStatus, mutable.ListBuffer.empty[(Long, Long)], active = false)
    case ItemAdded(orderId, itemId, price, true, operationId) =>
      val newItem: (Long, Long) = (itemId, price)
      copy(orderId = orderId, userId = userId, paymentStatus = paymentStatus, items += newItem, active = active)
    case ItemRemoved(orderId, itemId, true, operationId) =>
      copy(orderId = orderId, userId = userId, items = items.filterNot(element => element._1 == itemId), active = active)
    case OrderCheckedOut(orderId, true) =>
      copy(orderId = orderId, userId = userId, paymentStatus = true, items = items, active = active)
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

/**
  * Holds all orders
  */
case class OrderRepositoryState(orders: mutable.Map[Long, OrderState], currentOperations: PushOutHashmapQueueBuffer[Long, Event], executingSagasRequester: mutable.Map[Long, ActorRef]) extends PersistentActorState {
  override var doneOperations: PushOutHashmapQueueBuffer[Long, Event] = currentOperations

  def updated(event: MessageTypes.Event): OrderRepositoryState = event match {
    case OrderCreated(orderId, userId) =>
      copy(orders += orderId -> new OrderState(-1, -1, false, mutable.ListBuffer.empty[(Long, Long)], false).updated(event))
    case OrderDeleted(orderId, true) =>
      orders.get(orderId).get.updated(event)
      copy(orders -= orderId)
    case ItemAdded(orderId, _, _, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(orders += orderId -> orders.get(orderId).get.updated(event))
    case ItemRemoved(orderId, _, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(orders += orderId -> orders.get(orderId).get.updated(event))
    case OrderCheckedOut(orderId, true) =>
      executingSagasRequester -= orderId
      copy(orders += orderId -> orders.get(orderId).get.updated(event))

    /**
      * Ignore these events, they have no state changes
      */
    case ItemRemoved(_, _, false, _) | ItemAdded(_, _, _, false, _) | OrderCheckedOut(_, false) => this
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for OrderActor.")
  }
}

/**
  * Actor storing the current state of a order.
  * All valid commands/ queries for orders are resolved here and then send back to the requesting actor
  */
class OrderRepository extends PersistentActorBase with CommandHandler {
  override var state: PersistentActorState = new OrderRepositoryState(mutable.Map.empty[Long, OrderState], new PushOutHashmapQueueBuffer[Long, Event](500), mutable.Map.empty[Long, ActorRef])

  implicit val executor: ExecutionContext = context.system.dispatcher
  val randomGenerator: scala.util.Random = scala.util.Random

  val config: Config = ConfigFactory.load("order-service.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

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
      case state@OrderRepositoryState(_, _, _) => return state
      case state => throw new Exception("Invalid OrderRepository state type for order-repository: " + persistenceId + ". A state ActorState.OrderRepositoryState type was expected, but " + state.getClass.toString + " was found.")
    }
  }

  def getOrderState(orderId: Long): Option[OrderState] = {
    getOrderRepository().orders.get(orderId)
  }

  def buildResponseEvent(request: MessageTypes.Request): Event = {
    request match {
      case CreateOrder(orderId, userId) => return OrderCreated(orderId, userId)

      case DeleteOrder(orderId) => return OrderDeleted(orderId, true)

      case AddItem(orderId, itemId, operationId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new InvalidIdException("Order does not exist."))
        val success = !orderState.paymentStatus
        return ItemAdded(orderId, itemId, randomGenerator.nextInt(100).toLong, success, operationId)

      case RemoveItem(orderId, itemId, operationId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new InvalidIdException("Order does not exist."))
        val success = !orderState.paymentStatus && orderState.items.contains(itemId)
        return ItemRemoved(orderId, itemId, success, operationId)

      case FindOrder(orderId) =>
        val orderState: OrderState = getOrderState(orderId).getOrElse(throw new InvalidIdException("Order does not exist."))
        return OrderFound(orderId, orderState.userId, orderState.paymentStatus, orderState.items.toList)

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }


  def specialReceiveCommands: Receive = {
    case check: CheckoutOrder =>
      getOrderRepository().executingSagasRequester += check.orderId -> sender()
      val orderState: OrderState = getOrderState(check.orderId).getOrElse(throw new InvalidIdException("Order does not exist."))
      if (orderState.paymentStatus) OrderCheckedOut(check.orderId, true)

      val saga: Saga = createCheckoutSaga(orderState, check.orderId, config)
      implicit val timeout: Timeout = new Timeout(saga.maxTimeoutTime  * 2)
      check.secShardingActor ! ExecuteSaga(saga, self)

    case sagaCompleted: SagaCompleted =>
      val requester: ActorRef = getOrderRepository().executingSagasRequester.getOrElse(sagaCompleted.saga.id, throw new Exception("Saga requester not found for response"))
      requester ! OrderCheckedOut(sagaCompleted.saga.id, true)
    case sagaAborted: SagaFailed =>
      val requester: ActorRef = getOrderRepository().executingSagasRequester.getOrElse(sagaAborted.saga.id, throw new Exception("Saga requester not found for response"))
      requester ! OrderCheckedOut(sagaAborted.saga.id, false)

  }
  override  def receiveCommand = specialReceiveCommands.orElse(super.receiveCommand)


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
      case AddItem(orderId, itemId, _) =>
        var itemExists : Boolean = false
        val finalUri = config.getString("loadbalancer.stock.uri") + "/stock/availability/" + itemId
        implicit val executor: ExecutionContext = context.dispatcher
        implicit val system: ActorSystem = context.system
        val responseFuture: Future[Any] = HttpHelper.sendRequest(HttpRequest(method = HttpMethods.GET, uri = finalUri))

        responseFuture.onComplete {
          case Success(response: HttpResponse) => itemExists = response.entity.toString.contains("AvailabilityGot") && getOrderState(request.key).getOrElse(return false).active
          case Failure(exception) => itemExists =  false
        }

        while(!responseFuture.isCompleted) Thread.sleep(15)

        return itemExists

      case _ => return getOrderState(request.key).getOrElse(return false).active
    }
  }

  def createCheckoutSaga(order: OrderState, sagaId: Long, config: Config): Saga = {
    val orderSaga: Saga = new Saga(sagaId)

    for ((itemId, _) <- order.items) {
      orderSaga.addConcurrentOperation(createDecreaseStockOperation(itemId, 1, config.getString("loadbalancer.stock.uri")))
    }
    orderSaga.addConcurrentOperation(createPayOrderOperation(order.orderId, order.userId, config.getString("loadbalancer.payment.uri")))
    return orderSaga
  }

  def createPayOrderOperation(orderId: Long, userId: Long, paymentServiceUri: String): SagaOperation = {
    val paymentPostCondition: String => Boolean = new Function[String, Boolean] {
      //ToDo: actually check for events not for strings
      override def apply(result: String): Boolean = return result.contains("PaymentPayed") && result.contains("true") && result.contains(orderId.toString) && result.contains(userId.toString)
    }

    return SagaOperation(
      URI.create(paymentServiceUri + "/payment/pay/" + userId + "/" + orderId),
      URI.create(paymentServiceUri + "/payment/cancelPayment/" + userId + "/" + orderId),
      paymentPostCondition)
  }

  def createDecreaseStockOperation(itemId: Long, amount: Long, stockServiceUri: String): SagaOperation = {
    val decreaseStockPostCondition: String => Boolean = new Function[String, Boolean] {
      //ToDo: actually check for events not for strings
      override def apply(result: String): Boolean = result.contains("AvailabilityDecreased") && result.contains("true") && result.contains(itemId.toString)
    }

    return SagaOperation(
      URI.create(stockServiceUri + "/stock/subtract/" + itemId + "/" + amount),
      URI.create(stockServiceUri + "/stock/add/" + itemId + "/" + amount),
      decreaseStockPostCondition)
  }
}
