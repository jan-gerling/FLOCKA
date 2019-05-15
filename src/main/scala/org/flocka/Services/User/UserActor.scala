package org.flocka.Services.User

import akka.actor._
import akka.persistence._
import UserCommunication._
import java.util.UUID.randomUUID

case class UserState(userId: Long,
                     active: Boolean,
                     credit: Long) {

  def updated(event: Event): UserState = event match {
    case UserCreated(userId) =>
      copy(userId = userId, active = true, 0)

    case UserDeleted(userId, true) =>
      copy(userId = userId, active = false, credit = credit)

    case CreditAdded(userId, amount, true) =>
      copy(userId = userId, active = active, credit = credit + amount)

    case CreditSubtracted(userId, amount, true) =>
      copy(userId = userId, active = active, credit = credit - amount)
  }
}

class UserActor() extends PersistentActor{
  override def persistenceId = randomUUID().getLeastSignificantBits.toString

  var state = UserState(persistenceId.toLong, false, 0)

  def updateState(event: Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: Event                             => updateState(evt)
    case SnapshotOffer(_, snapshot: UserState) => state = snapshot
  }

  //TODO figure out good interval value
  val snapShotInterval = 1000
  val receiveCommand: Receive = {
    case CreateUser() =>
      //how to check if successful?
      persist(UserCreated(persistenceId.toLong)) { event =>
        updateState(event)
        sender() ! event
        //publish on event stream? https://doc.akka.io/api/akka/current/akka/event/EventStream.html
      }

    case DeleteUser(userId) =>
      //how to check if successful?
      persist(UserDeleted(userId, true)) { event =>
        updateState(event)
        sender() ! event
    }

    case FindUser(userId) =>
      //NoOp -> no persistence necessary
      sender ! (UserFound(userId, Set(userId, state.credit)))

    case GetCredit(userId) =>
      //NoOp -> no persistence necessary
      sender ! (CreditGot(userId, state.credit))

    case AddCredit(userId, amount) =>
      //how to check if successful?
      persistAsync(CreditAdded(userId, amount, true)) { event =>
        updateState(event)
        sender() ! event
      }

    case SubtractCredit(userId, amount) =>
      //how to check if successful?
      persistAsync(CreditSubtracted(userId, amount, true)) { event =>
        updateState(event)
        sender() ! event
      }
  }
}
