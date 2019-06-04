package org.flocka.Services.Payment

import java.util.concurrent.TimeoutException

import akka.actor.{Props, _}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.persistence.SnapshotOffer
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.PersistentActorBase.InvalidPaymentException
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.Payment.PaymentServiceComs._
import org.flocka.Utils.PushOutHashmapQueueBuffer

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

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

    case PaymentPayed(userId, orderId, true, _) =>
      copy(userId = userId, orderId = orderId, status = true)

    case PaymentPayed(userId, orderId, false, _) =>
      copy(userId = userId, orderId = orderId, status = false)

    case PaymentCanceled(userId, orderId, true, _) =>
      copy(userId = userId, orderId = orderId, status = true)

    case PaymentCanceled(userId, orderId, false, _) =>
      copy(userId = userId, orderId = orderId, status = false)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for PaymentActor.")
  }
}

case class PaymentRepositoryState(payments: mutable.Map[Long, PaymentState], currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations = currentOperations

  def updated(event: MessageTypes.Event): PaymentRepositoryState = event match {
    case PaymentPayed(userId, orderId, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(payments += orderId -> new PaymentState(userId, orderId, true).updated(event))
    case PaymentCanceled(userId, orderId, false, operationId) =>
      doneOperations.push(operationId, event)
      copy(payments += orderId -> new PaymentState(userId, orderId, false).updated(event))
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

  val MAX_NUM_TRIES = 5

  val config: Config = ConfigFactory.load("payment-service.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

  val loadBalancerURI: String = config.getString("clustering.loadbalancer.uri")

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
      case PayPayment(userId, orderId, operationId) =>
        val orderUri = loadBalancerURI + "/orders/find/" + orderId
        val futureOrder: Future[HttpResponse] = sendRequest(orderUri)

        Await.result(futureOrder.map { response =>
          val amount = getOrderAmount(response.toString())
          val decreaseCreditUri = loadBalancerURI + "/users/credit/subtract/" + userId + "/" + amount
          val futureCredit: Future[HttpResponse] = sendRequest(decreaseCreditUri)
          Await.result[Event](futureCredit.map { response =>
            val success: Boolean = response.toString.contains("CreditSubtracted") && response.toString.contains("true")
            PaymentPayed(userId, orderId, success, operationId)
          }, 500 millisecond)
        }, 500 millisecond)

      case CancelPayment(userId, orderId, operationId) =>
        val orderUri = loadBalancerURI + "/orders/find/" + orderId
        val futureOrder: Future[HttpResponse] = sendRequest(orderUri)

        Await.result(futureOrder.map { response =>
          val amount = getOrderAmount(response.toString())
          val decreaseCreditUri = loadBalancerURI + "/users/credit/add/" + userId + "/" + amount
          val futureCredit: Future[HttpResponse] = sendRequest(decreaseCreditUri)
          Await.result[Event](futureCredit.map { response =>
            val success: Boolean = response.toString.contains("CreditAdded") && response.toString.contains("true")
            PaymentCanceled(userId, orderId, success, operationId)
          }, 500 millisecond)
        }, 500 millisecond)

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
      case GetPaymentStatus(orderId)=>
        if (getPaymentState(orderId).isDefined)
          return true
        else throw new InvalidPaymentException("Payment with " + orderId.toString + "does not exist.")
    }
  }


  private def getOrderAmount(response: String) : Long ={
    var amount : Long = 0
    var currTuple = ""
    if (response.contains("List((")) { //has more than one item
      val listOfTuples = response.split("List(")(1).split(",")
      for (tuple <- listOfTuples) {
        currTuple = tuple replaceAll ("(","")
        currTuple = tuple replaceAll (")","")
        amount += currTuple.split(",")(1).toLong
      }
    }
    return amount
  }

  private def sendRequest(path: String, numTries: Int = 0)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[HttpResponse] = {


    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(numTries seconds)
    val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    return Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = path.toString), settings = connectionPoolSettings).recover{
      case exception: TimeoutException =>
        if(numTries >= MAX_NUM_TRIES){
          return Future.failed(exception)
        }else {
          return sendRequest(path, numTries + 1)
        }
    }
  }
}