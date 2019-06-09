package org.flocka.Services.Payment


import akka.actor.Props
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}

import akka.persistence.SnapshotOffer
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.PersistentActorBase.{InvalidOperationException, InvalidPaymentException}
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.Payment.PaymentServiceComs._

import org.flocka.Utils.{PushOutHashmapQueueBuffer}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.StreamTcpException
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext}


/**
  * PaymentActor props and custom exceptions for a PaymentActor.
  */
object PaymentRepository {
  def props(): Props = Props(new PaymentRepository())
}

/*
Hold the current state of the Payment here.
PaymentId matches the persistenceId and is the unique identifier for payment and actor.
active identifies if the payment is still an active payment, or needs to be deleted
 */
case class PaymentState(userId: Long,
                        orderId: Long,
                        status: Boolean) {

  def updated(event: MessageTypes.Event): PaymentState = event match {

    case PaymentPayed(userId, orderId, status, _) =>
      copy(userId = userId, orderId = orderId, status = status)

    case PaymentCanceled(userId, orderId, status, _) =>
      copy(userId = userId, orderId = orderId, status = !status)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for PaymentActor.")
  }
}

case class PaymentRepositoryState(payments: mutable.Map[Long, PaymentState], currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations = currentOperations

  def updated(event: MessageTypes.Event): PaymentRepositoryState = event match {
    case PaymentPayed(userId, orderId, status, operationId) =>
      doneOperations.push(operationId, event)
      payments -= orderId
      payments += orderId -> new PaymentState(userId, orderId, true)
      copy(payments)
    case PaymentCanceled(userId, orderId, status, operationId) =>
      doneOperations.push(operationId, event)
      payments -= orderId
      payments += orderId -> new PaymentState(userId, orderId, false)
      copy(payments)
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for PaymentActor.")
  }

  /**
    * holds the last n amount of successfully carried out commands with an operation id on this repositories
    */
}

/**
  * Actor storing the current state of a payment.
  * All valid commands/ queries for payments are resolved here and then send back to the requesting actor (supposed to be PaymentService via PaymentActorSupervisor).
  */
class PaymentRepository extends PersistentActorBase {
  override var state: PersistentActorState = new PaymentRepositoryState(mutable.Map.empty[Long, PaymentState], new PushOutHashmapQueueBuffer[Long, Event](500))

  implicit val system: ActorSystem = context.system
  implicit val executor: ExecutionContext = system.dispatcher

  val MAX_NUM_TRIES = 3

  val TIMEOUT_TIME = 10000


  val config: Config = ConfigFactory.load("payment-service.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

  val loadBalancerURIOrder: String = config.getString("loadbalancer.order.uri")
  val loadBalancerURIUser: String = config.getString("loadbalancer.user.uri")

  // Since we have millions of users, we should passivate quickly
  context.setReceiveTimeout(passivateTimeout)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case SnapshotOffer(_, snapshot: PaymentRepositoryState) => state = snapshot
  }

  def getPaymentRepository(): PaymentRepositoryState = {
    state match {
      case state@PaymentRepositoryState(_, _) => return state
      case state => throw new Exception("Invalid payment-repository state type for payment-repository: " + persistenceId + ".A state ActorState.PaymentRepository type was expected, but " + state.toString + " was found.")
    }
  }

  def getPaymentState(orderId: Long): Option[PaymentState] = {
    getPaymentRepository().payments.get(orderId)
  }

  def buildResponseEvent(request: MessageTypes.Request): Event = {
    request match {

      case pay@PayPayment(userId, orderId, operationId) =>
        var resultEvent: PaymentPayed = new PaymentPayed(-1, -1, false, -1)
        val orderUri = loadBalancerURIOrder + "/orders/find/" + orderId
        val orderDetails: String  = "OrderFound"

    val future = for {
          creditDetails: HttpResponse <-
          orderDetails.toString.contains("OrderFound") match {
            case true => val amount = 100
              val decreaseCreditUri = loadBalancerURIUser + "/users/credit/subtract/" + pay.userId + "/" + amount
              Http(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = decreaseCreditUri))

          }} yield {
          val creditSubtracted: Boolean = creditDetails.entity.toString.contains("CreditSubtracted") && creditDetails.entity.toString.contains("true")
          resultEvent = PaymentPayed(userId, orderId, creditSubtracted, operationId)
        }
        future.recover {
          case _: InvalidOperationException => resultEvent = PaymentPayed(userId, orderId, false, operationId)
          case _: StreamTcpException => resultEvent = PaymentPayed(userId, orderId, false, operationId)
          case exception@_ => resultEvent = PaymentPayed(userId, orderId, false, operationId)
        }

        var elapsedTime: Long = 0
        while (resultEvent.userId < 0) {
          if (elapsedTime > TIMEOUT_TIME) {
            throw new Exception("Timeout!")
          }
          elapsedTime += 15
          Thread.sleep(15)
        }
        resultEvent

      case cancelPay@CancelPayment(userId, orderId, operationId) =>
        var resultEvent: PaymentCanceled = PaymentCanceled(-1, -1, false, -1)
        val orderUri = loadBalancerURIOrder + "/orders/find/" + orderId
        val future = for {
          orderDetails: HttpResponse <- Http(system).singleRequest(HttpRequest(method = HttpMethods.GET, uri = orderUri))
          creditDetails: HttpResponse <-
          orderDetails.entity.toString.contains("OrderFound") match {
            case true => val amount = getTotalOrderCost(orderDetails.entity.toString)
              val increaseCreditUri = loadBalancerURIUser + "/users/credit/add/" + userId + "/" + amount
              Http(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = increaseCreditUri))
            case false => throw new InvalidOperationException(persistenceId, cancelPay.toString)
          }} yield {
          val creditAdded: Boolean = creditDetails.toString.contains("CreditAdded") && creditDetails.toString.contains("true")
          resultEvent = PaymentCanceled(userId, orderId, creditAdded, operationId)
        }
        future.recover {
          case _: InvalidOperationException => resultEvent = PaymentCanceled(userId, orderId, false, operationId)
          case _: StreamTcpException => resultEvent = PaymentCanceled(userId, orderId, false, operationId)
          case exception@_ => println(exception); resultEvent = PaymentCanceled(userId, orderId, false, operationId)
        }


        var elapsedTime: Long = 0
        while (resultEvent.userId < 0) {
          if (elapsedTime > TIMEOUT_TIME) {
            throw new Exception("Timeout!")
          }
          elapsedTime += 15
          Thread.sleep(15)
        }
        resultEvent
      case GetPaymentStatus(orderId) =>
        val paymentState: PaymentState = getPaymentState(orderId).getOrElse(throw new InvalidPaymentException(orderId.toString))
        return PaymentStatusFound(orderId, paymentState.status)

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case PayPayment(_, _, _) =>
        return true
      case CancelPayment(userId, orderId, _) =>
        if (getPaymentState(orderId).isDefined)
          return true
        else if (getPaymentState(orderId).get.userId != userId)
          throw new IllegalAccessException("Different user made this payment.")
        else throw new InvalidPaymentException("Payment with " + orderId.toString + "does not exist.")
      case GetPaymentStatus(orderId) =>
        if (getPaymentState(orderId).isDefined)
          return true
        else throw new InvalidPaymentException("Payment with " + orderId.toString + "does not exist.")
    }
  }

  private def getTotalOrderCost(response: String): Long = {
    var amount: Long = 0
    var currTuple = ""
    if (response.contains("List((")) { //has more than one item
      val listOfTuples = response.split("List\\(")(1).split("\\), \\(")
      for (tuple <- listOfTuples) {
        currTuple = tuple replaceAll("\\(", "")
        currTuple = tuple replaceAll("\\)", "")
        amount += currTuple.split(",")(1).toLong
      }
    }
    return amount
  }
}