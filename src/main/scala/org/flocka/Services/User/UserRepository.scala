package org.flocka.Services.User

import akka.actor.{Props, _}
import akka.persistence.SnapshotOffer
import com.typesafe.config.{Config, ConfigFactory}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.ServiceBasics.{MessageTypes, PersistentActorBase, PersistentActorState}
import org.flocka.ServiceBasics.IdResolver.InvalidIdException
import org.flocka.Services.User.UserServiceComs._
import org.flocka.Utils.PushOutHashmapQueueBuffer
import scala.collection.mutable
import scala.concurrent.duration._

/**
  * UserActor props and custom exceptions for a UserActor.
  */
object UserRepository {
  def props(): Props = Props(new UserRepository())
}

/**
  * Hold the current state of the user here.
  *
  * @param userId matches the persistenceId and is the unique identifier for user and actor.
  * @param active identifies if the user is still an active user, or needs to be deleted
  * @param credit is the current credit of this user
  */
case class UserState(userId: Long,
                     active: Boolean,
                     credit: Long) {
  def updated(event: MessageTypes.Event): UserState = event match {
    case UserCreated(userId) =>
      copy(userId = userId, active = true, 0)

    case UserDeleted(userId, true) =>
      copy(userId = userId, active = false, credit = credit)

    case CreditAdded(userId, amount, true,_) =>
      copy(userId = userId, active = active, credit = credit + amount)

    case CreditSubtracted(userId, amount, true, _) =>
      copy(userId = userId, active = active, credit = credit - amount)

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

case class UserRepositoryState(users: mutable.Map[Long, UserState], currentOperations: PushOutHashmapQueueBuffer[Long, Event]) extends PersistentActorState {
  override var doneOperations = currentOperations

  def updated(event: MessageTypes.Event): UserRepositoryState = event match {
    case UserCreated(userId) =>
      copy(users += userId -> new UserState(-1, false, -1).updated(event))
    case UserDeleted(userId, true) =>
      copy(users += userId -> users.get(userId).get.updated(event))
    case CreditAdded(userId, amount, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(users += userId -> users.get(userId).get.updated(event))
    case CreditSubtracted(userId, amount, true, operationId) =>
      doneOperations.push(operationId, event)
      copy(users += userId -> users.get(userId).get.updated(event))

    /**
    Ignore these events, they have no state changes
      */
    case CreditSubtracted(_, _, false, _) => this
    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

/**
  * Actor storing the current state of a user.
  * All valid commands/ queries for users are resolved here and then send back to the requesting actor (supposed to be UserService via UserActorSupervisor).
  */
class UserRepository extends PersistentActorBase {
  override var state: PersistentActorState = new UserRepositoryState(mutable.Map.empty[Long, UserState], new PushOutHashmapQueueBuffer[Long, Event](500))

  val config: Config = ConfigFactory.load("user-service.conf")
  val passivateTimeout: FiniteDuration = config.getInt("sharding.passivate-timeout") seconds
  val snapShotInterval: Int = config.getInt("sharding.snapshot-interval")

  // Since we have millions of users, we should passivate quickly
  context.setReceiveTimeout(passivateTimeout)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case SnapshotOffer(_, snapshot: UserRepositoryState) => state = snapshot
  }

  def getUserRepository(): UserRepositoryState = {
    state match {
      case state@UserRepositoryState(_, _) => return state
      case state => throw new Exception("Invalid user-repository state type for user-repository: " + persistenceId + ".A state ActorState.UserRepository type was expected, but " + state.toString + " was found.")
    }
  }

  def getUserState(userId: Long): Option[UserState] = {
    getUserRepository().users.get(userId)
  }

  def buildResponseEvent(request: MessageTypes.Request): Event = {
    request match {
      case CreateUser(userId) => return UserCreated(userId.toLong)

      case DeleteUser(userId) => return UserDeleted(userId, true)

      case AddCredit(userId, amount, operationId) => return CreditAdded(userId, amount, true, operationId)

      case SubtractCredit(userId, amount, operationId) =>
        val userState = getUserState(userId).getOrElse(throw new InvalidIdException("User does not exist."))
        if (!userState.active)
          throw new Exception("User does not exist.")
        CreditSubtracted(userId, amount, userState.credit >= amount, operationId)

      case GetCredit(userId) => return CreditGot(userId, getUserState(userId).get.credit)

      case _ => throw new IllegalArgumentException(request.toString)
    }
  }


  def validateState(request: MessageTypes.Request): Boolean = {
    request match {
      case CreateUser(userId) =>
        if (getUserState(userId).isDefined) {
          return false
        }
        else
          return true
      case _ => return getUserState(request.key).getOrElse(return false).active
    }
  }
}