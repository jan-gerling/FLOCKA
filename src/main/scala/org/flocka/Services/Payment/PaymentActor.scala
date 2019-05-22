package org.flocka.Services.Payment

import akka.actor._
import PaymentServiceComs._
import akka.actor.Props
import akka.persistence.{PersistentActor, SnapshotOffer}
import org.flocka.MessageTypes
import org.flocka.Services.User.UserActor.UserActorTimeoutException

import scala.concurrent.duration._

/*
Hold the current state of the Payment here.
PaymentId matches the persistenceId and is the unique identifier for payment and actor.
active identifies if the user is still an active user, or needs to be deleted
 */
case class PaymentState(userId: Long,
                        orderId: Long,
                        status: Boolean) {

  def updated(event: MessageTypes.Event): PaymentState = event match {

    case PaymentPayed(userId, orderId) =>
      copy(userId = userId, orderId = orderId, status = true)

    case PaymentCanceled(userId, orderId) =>
      copy(userId = userId, orderId = orderId, status = false)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

object PaymentActor{
  def props(): Props = Props(new PaymentActor())
  case class InvalidUserException(userId: String) extends Exception("This user: " + userId + " is not active.")
  case class InvalidOrderException(orderId: String) extends Exception("This order: " + orderId + " is not active.")
  case class PaymentActorTimeoutException(orderId: String) extends Exception(orderId)
}

class PaymentActor() extends PersistentActor{
  override def persistenceId = self.path.name

  //ToDo: what is a good timeout time for a paymentActor?
  context.setReceiveTimeout(150 seconds)
  var state = PaymentState(persistenceId.toLong, -1, false)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event                  => updateState(evt)
    case SnapshotOffer(_, snapshot: PaymentState) => state = snapshot
  }

  def queryHandler(query: MessageTypes.Query, orderId: Long, event: MessageTypes.Event ): Unit = {
    //ToDo: Check if we can assume that the message always arrives at the correct user actor
    try {
      if(validateState(query))
        sender() ! event
    } catch {
      case userException: PaymentActor.InvalidUserException => sender() ! akka.actor.Status.Failure(userException)
      case ex: Exception => throw ex
    }
  }

  def commandHandler(command: MessageTypes.Command, userId: Long, orderId: Long, event: MessageTypes.Event ): Unit = {
    try {
      if(validateState(command)) persist(event) { event =>
        updateState(event)
        sender() ! event

        context.system.eventStream.publish(event)
        if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
          saveSnapshot(state)

        //publish on event stream? https://doc.akka.io/api/akka/current/akka/event/EventStream.html
      }

    } catch {
      case userException: PaymentActor.InvalidUserException => sender() ! akka.actor.Status.Failure(userException)
      case ex: Exception => throw ex
    }
  }

  /*
  Validate if the user actor state allows any interaction at the current point
   */
  def validateState(command: MessageTypes.Command): Boolean ={
    if (command == PaymentServiceComs.CreateUser() && state.active == false) {
      return true
    } else if(state.active) {
      return  true
    } else if (command != PaymentServiceComs.CreateUser() && state.active == false) {
      throw new PaymentActor.InvalidUserException(state.orderId.toString)
    }

    throw new IllegalArgumentException(command.toString)
  }

  def validateState(query: MessageTypes.Query): Boolean ={
    if(state.active) {
      return  true
    }

    throw new UserActor.InvalidUserException(state.userId.toString)
  }

  //TODO figure out good interval value
  val snapShotInterval = 1000
  val receiveCommand: Receive = {
    case command @ CreateUser() =>
      commandHandler(command, persistenceId.toLong, UserCreated(persistenceId.toLong))

    case command @  DeleteUser(userId) =>
      commandHandler(command, userId, UserDeleted(state.userId, true))

    case query @ FindUser(userId) =>
      queryHandler(query, userId, (UserFound(userId, Set(userId, state.credit))))

    case query @ GetCredit(userId) =>
      queryHandler(query, userId, (CreditGot(userId, state.credit)))

    case command @ AddCredit(userId, amount) =>
      commandHandler(command, userId, CreditAdded(userId, amount, true))

    case command @ SubtractCredit(userId, amount) =>
      commandHandler(command, userId, CreditSubtracted(userId, amount, true))

    case ReceiveTimeout =>
      throw new UserActorTimeoutException(persistenceId)
  }

  /*
  For Debugging only.
  override def preStart() = println("User actor: " + persistenceId + " at " + self.path + " was started.")
  override def postStop() = println("User actor: " + persistenceId + " at " + self.path + " was shut down.")
  override def preRestart(reason: Throwable, message: Option[Any]) = {
    println("User actor: " + persistenceId + " at " + self.path + " is restarting.")
    super.preRestart(reason, message)
  }
  override def postRestart(reason: Throwable) = {
    println("User actor: " + persistenceId + " at " + self.path + " has restarted.")
    super.postRestart(reason)
  }
  End Debugging only.
  */
}