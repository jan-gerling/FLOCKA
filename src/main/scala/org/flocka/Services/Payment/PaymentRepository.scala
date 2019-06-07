package org.flocka.Services.Payment

import java.util.concurrent.TimeoutException

import akka.actor.{Props, _}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethod, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.persistence.SnapshotOffer
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.PersistentActorBase.{InvalidOperationException, InvalidPaymentException}
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.Payment.PaymentServiceComs._
import org.flocka.Utils.PushOutHashmapQueueBuffer

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

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
      copy(userId = userId, orderId = orderId, status = status)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for PaymentActor.")
  }
}

case class PaymentRepositoryState(payments: mutable.Map[Long, PaymentState], currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations = currentOperations

  def updated(event: MessageTypes.Event): PaymentRepositoryState = event match {
    case PaymentPayed(userId, orderId, status, operationId) =>
      doneOperations.push(operationId, event)
      copy(payments += orderId -> new PaymentState(userId, orderId, true).updated(event))
    case PaymentCanceled(userId, orderId, status, operationId) =>
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

  val MAX_NUM_TRIES = 3
  val TIMEOUT_TIME = 4000 millisecond

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
      case PayPayment(userId, orderId, operationId) =>
        var elapsedTime: Long = 0
        var done: Boolean = false
        var resultEvent: PaymentPayed = PaymentPayed(userId, -1, false, operationId)
        val orderUri = loadBalancerURIOrder + "/orders/find/" + orderId
        val futureOrder: Future[HttpResponse] = sendRequest(HttpMethods.GET, orderUri)
        futureOrder.map { response =>
          if (checkIfOrderExist(response.toString())){
            val amount = getTotalOrderCost(response.entity.toString())
            val decreaseCreditUri = loadBalancerURIUser + "/users/credit/subtract/" + userId + "/" + amount
            val futureCredit: Future[HttpResponse] = sendRequest(HttpMethods.POST, decreaseCreditUri)
            futureCredit.map { response =>
              println(response.entity)
              println(response.entity.toString.contains("CreditSubtracted"))
              println(response.entity.toString.contains("true"))
              val success: Boolean = response.entity.toString.contains("CreditSubtracted") && response.entity.toString.contains("true")
              println(success)
              resultEvent = PaymentPayed(userId, orderId, success, operationId)
              done = true
            }
          }
          else {
            done = true
          }
        }
        while(!done && elapsedTime < TIMEOUT_TIME.toMillis){
          elapsedTime += 15
          Thread.sleep(15)
        }
        println(resultEvent)
        return resultEvent
      case CancelPayment(userId, orderId, operationId) =>
        var elapsedTime: Long = 0
        var done: Boolean = false
        var resultEvent: PaymentCanceled = PaymentCanceled(userId, operationId, false, operationId)
        val orderUri = loadBalancerURIOrder + "/orders/find/" + orderId
        val futureOrder: Future[HttpResponse] = sendRequest(HttpMethods.GET, orderUri)
        futureOrder.map { response =>
          if (checkIfOrderExist(response.toString())){
            val amount = getTotalOrderCost(response.entity.toString())
            val increaseCreditUri = loadBalancerURIUser + "/users/credit/add/" + userId + "/" + amount
            val futureCredit: Future[HttpResponse] = sendRequest(HttpMethods.POST, increaseCreditUri)
            futureCredit.map { response =>
              val success: Boolean = response.toString.contains("CreditAdded") && response.toString.contains("true")
              resultEvent = PaymentCanceled(userId, orderId, success, operationId)
              done = true
            }
          }
          else {
            done = true
          }
        }
        while(!done && elapsedTime < TIMEOUT_TIME.toMillis){
          elapsedTime += 15
          Thread.sleep(15)
        }
        return resultEvent

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

  private def checkIfOrderExist(response: String) : Boolean ={
    return response.contains("OrderFound")
  }

  private def getTotalOrderCost(response: String) : Long ={
    var amount : Long = 0
    var currTuple = ""
    if (response.contains("List((")) { //has more than one item
      val listOfTuples = response.split("List\\(")(1).split("\\), \\(")
      for (tuple <- listOfTuples) {
        currTuple = tuple replaceAll ("\\(","")
        currTuple = tuple replaceAll ("\\)","")
        amount += currTuple.split(",")(1).toLong
      }
    }
    return amount
  }

  def sendRequest(method: HttpMethod = HttpMethods.POST, path: String, numTries: Int = 0): Future[HttpResponse] = {
    Http().singleRequest(HttpRequest(method = method, uri = path.toString)).recover{
      case exception: TimeoutException =>
        if(numTries >= 3){
          println(exception)
          return Future.failed(exception)
        }else {
          println(exception)
          return sendRequest(method, path, numTries + 1)
        }
      case ex@ _ => throw new Exception(ex)
    }
  }
}