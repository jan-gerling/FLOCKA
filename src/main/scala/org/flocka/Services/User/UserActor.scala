package org.flocka.Services.User

import akka.actor.{Props, _}
import akka.persistence.SnapshotOffer
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.Services.User.UserActor.conf
import org.flocka.Services.User.UserServiceComs._

import scala.concurrent.duration._

/**
  * UserActor props and custom exceptions for a UserActor.
  */
object UserActor{
  def props(): Props = Props(new UserActor())

  val conf: Config = ConfigFactory.load()
}

/**
Hold the current state of the user here.
  @param userId matches the persistenceId and is the unique identifier for user and actor.
@param active identifies if the user is still an active user, or needs to be deleted
@param credit is the current credit of this user
  */
case class UserState(userId: Long,
                     active: Boolean,
                     credit: Long) extends PersistentActorState{

  def updated(event: MessageTypes.Event): UserState = event match {
    case UserCreated(userId) =>
      copy(userId = userId, active = true, 0)

    case UserDeleted(userId, true) =>
      copy(userId = userId, active = false, credit = credit)

    case CreditAdded(userId, amount, true) =>
      copy(userId = userId, active = active, credit = credit + amount)

    case CreditSubtracted(userId, amount, true) =>
      copy(userId = userId, active = active, credit = credit - amount)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

/**
  * Actor storing the current state of a user.
  * All valid commands/ queries for users are resolved here and then send back to the requesting actor (supposed to be UserService via UserActorSupervisor).
  * Implements the ActorSupervisorBase class.
  */
class UserActor extends PersistentActorBase {
  override var state: PersistentActorState = new UserState(persistenceId.toLong, false, 0)

  val passivateTimeout: FiniteDuration = conf.getInt("user.passivate-timeout") seconds
  val snapShotInterval: Int = conf.getInt("user.snapshot-interval")
  // Since we have millions of users, we should passivate quickly
  context.setReceiveTimeout(passivateTimeout)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event                         => updateState(evt)
    case SnapshotOffer(_, snapshot: UserState) => state = snapshot
  }

  def getUserState(): UserState ={
    state match {
      case state @ UserState(userId, active, credit) => return state
      case state => throw new Exception("Invalid user state type for user: " + persistenceId + ".A state ActorState.UserState type was expected, but " + state.toString + " was found.")
    }
  }

  def buildResponseEvent(request: MessageTypes.Request): Event ={
    if(validateState(request) == false){
      sender() ! akka.actor.Status.Failure(PersistentActorBase.InvalidUserException(getUserState.userId.toString))
    }

    request match {
      case CreateUser(userId) =>
        if(userId == persistenceId.toLong) {
          return UserCreated(persistenceId.toLong)
        }
        throw new IllegalArgumentException(userId.toString)

      case DeleteUser(userId) => return UserDeleted(getUserState.userId, true)

      case AddCredit(userId, amount) => return CreditAdded(userId, amount, true)

      case SubtractCredit(userId, amount) => return CreditSubtracted(userId, amount, true)

      case FindUser(userId) => return UserFound(userId, Set(userId, getUserState.credit))

      case GetCredit(userId) => return CreditGot(userId, getUserState.credit)

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }

  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case UserServiceComs.CreateUser(_) => return getUserState.active == false
      case _ => return getUserState.active
    }
  }
}