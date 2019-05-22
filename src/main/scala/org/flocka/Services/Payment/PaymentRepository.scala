package org.flocka.Services.Payment

import akka.actor.{Props, _}
import akka.persistence.SnapshotOffer
import org.flocka.ServiceBasics
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.{Configs, MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.Payment.PaymentServiceComs._

import scala.collection.mutable
import scala.concurrent.duration._

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

    case PaymentPayed(userId, orderId, true) =>
      copy(userId = userId, orderId = orderId, status = true)

    case PaymentCanceled(userId, orderId) =>
      copy(userId = userId, orderId = orderId, status = false)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for PaymentActor.")
  }
}

case class PaymentRepositoryState(payments: mutable.Map[Long, PaymentState]) extends PersistentActorState {

  def updated(event: MessageTypes.Event): PaymentRepositoryState = event match {
    case PaymentPayed(userId, orderId, true) =>
      copy(payments += orderId -> new PaymentState(userId, orderId, true).updated(event))
    case PaymentCanceled(userId, orderId) =>
      copy(payments += orderId -> new PaymentState(userId, orderId, false).updated(event))
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for PaymentActor.")
  }
}

/**
  * Actor storing the current state of a payment.
  * All valid commands/ queries for payments are resolved here and then send back to the requesting actor (supposed to be PaymentService via PaymentActorSupervisor).
  */
class PaymentRepository extends PersistentActorBase {
  override var state: PersistentActorState = new PaymentRepositoryState(mutable.Map.empty[Long, PaymentState])

  val passivateTimeout: FiniteDuration = Configs.conf.getInt("payment.passivate-timeout") seconds
  val snapShotInterval: Int = Configs.conf.getInt("payment.snapshot-interval")
  // Since we have millions of payments, we should passivate quickly
  context.setReceiveTimeout(passivateTimeout)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case SnapshotOffer(_, snapshot: PaymentRepositoryState) => state = snapshot
  }

  def getPaymentRepository(): PaymentRepositoryState = {
    state match {
      case state@PaymentRepositoryState(payments) => return state
      case state => throw new Exception("Invalid payment-repository state type for payment-repository: " + persistenceId + ".A state ActorState.PaymentRepository type was expected, but " + state.toString + " was found.")
    }
  }

  def getPaymentState(orderId: Long): Option[PaymentState] = {
    getPaymentRepository().payments.get(orderId)
  }

  def buildResponseEvent(request: MessageTypes.Request): Event = {
    if (validateState(request) == false) {
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidPaymentException(request.key.toString))
    }

    request match {
      case PayPayment(userId, orderId) => return PaymentPayed(userId, orderId, true)

      case CancelPayment(userId, orderId) => return PaymentCanceled(userId, orderId)

      case GetPaymentStatus(orderId) => return PaymentStatusFound(orderId, Set(orderId, getPaymentState(orderId).get.status))

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case PayPayment(userId, orderId) =>
        return true
      case CancelPayment(userId, orderId) =>
        if (!getPaymentState(orderId).isDefined)
          return true
        else if (getPaymentState(orderId).get.userId != userId)
          throw new Exception("Different user made this payment.")
        else throw new Exception("Payment does not exist.")
    }
  }
}