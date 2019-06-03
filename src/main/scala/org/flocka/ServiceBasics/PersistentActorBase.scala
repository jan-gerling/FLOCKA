package org.flocka.ServiceBasics

import akka.actor.{PoisonPill, ReceiveTimeout}
import akka.persistence.PersistentActor
import org.flocka.ServiceBasics.MessageTypes.Event

import scala.concurrent.duration.FiniteDuration
import akka.actor._
import akka.cluster.sharding.ShardRegion.Passivate
import org.flocka.ServiceBasics.PersistentActorBase.InvalidOperationException
import org.flocka.Utils.PushOutHashmapQueueBuffer

/**
  * Base for all actor states of persistent actors.
  * For implementation reference see Services.User.UserActor
  */
trait PersistentActorState {
  /**
    * holds the last n amount of successfully carried out commands with an operation id on this repositories
    */
  var doneOperations: PushOutHashmapQueueBuffer[Long, Event]
  def updated(event: MessageTypes.Event): PersistentActorState
}

/**
  * Custom exceptions for all persistent actors, alter with care.
  */
object PersistentActorBase{
  case class InvalidOperationException(id: String, operation: String) extends Exception("Operation: " + operation + " is not valid for object: " + id)

  case class InvalidUserException(userId: String) extends Exception("This user: " + userId + " is not active.")
  case class InvalidPaymentException(orderId: String) extends Exception("This payment: " + orderId + " is not active.")
  case class InvalidStockException(itemId: String) extends Exception("This stock: " + itemId + " is not defined.")
  case class InvalidOrderException(orderId: String) extends Exception("This order: " + orderId + " is not defined.")
}

/**
  * Base class for all persistent actors, please implement this class, to propagate changes quickly in our system.
  * This class implements sending responses, persisting events, basic functionality for persistent actors.
  * You have to implement buildResponseEvent and validateState for your actors individually.
  * For implementation reference see Service.User.UserActor
  */
abstract class PersistentActorBase extends PersistentActor with QueryHandler {
  /**
    * Please don't touch!!!! It works!
    */
  override def persistenceId = getClass.getName + self.path.name

  val passivateTimeout: FiniteDuration
  val snapShotInterval: Int

  var state: PersistentActorState
  def updateState(event: MessageTypes.Event): Unit

  /**
    * Send the response to the original sender for the command/ query.
    * @param event the response to be returned to the serive
    */
  protected def sendResponse(event: MessageTypes.Event ): Unit = {
    sender() ! event
  }

  /**
    * Persist and if sussessful send the response to the original sender for the command/ query.
    * @param event the response to be returned to the serive
    */
  protected def sendPersistentResponse(event: MessageTypes.Event ): Unit = {
    persist(event) { event =>
      updateState(event)
      sender() ! event

      //publish on event stream? https://doc.akka.io/api/akka/current/akka/event/EventStream.html
      context.system.eventStream.publish(event)
      if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
        saveSnapshot(state)
    }
  }

  protected def respond(request: MessageTypes.Request): Unit = {
    if (!validateState(request)) {
      sender() ! akka.actor.Status.Failure(InvalidOperationException(request.key.toString, request.getClass.getName))
    } else {
      request match {
        case command: MessageTypes.Command => sendPersistentResponse(buildResponseEvent(command))
        case query: MessageTypes.Query => sendResponse(buildResponseEvent(query))
      }
    }
  }

  /**
    * Build a response event for the given request of type command or query. Validates the state of the actor before building the response.
    * @return the validated response as an event.
    */
  def buildResponseEvent(request: MessageTypes.Request): Event

  /**
    * Check if a request was already done.
    * @param request check if this operation was already done earlier
    * @return true if the operation was already done and its id is still buffered
    */
  private def getDoneEvent(request: MessageTypes.Request): Option[Event] = {
    request match {
      case command: MessageTypes.Command => state.doneOperations.getOption(command.operationId)
      case _ => None
    }
  }

  /**
  Validate if the user actor state allows any interaction with the given command.
    */
  def validateState(request: MessageTypes.Request): Boolean

  val receiveCommand: Receive = {
    case command: MessageTypes.Command =>
      getDoneEvent(command) match {
        case Some(event: Event) => sendResponse(event)
        case None => respond(command)
      }

    case query: MessageTypes.Query =>
      getDoneEvent(query) match {
        case Some(event: Event) => sendResponse(event)
        case None => respond(query)
      }

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }
}