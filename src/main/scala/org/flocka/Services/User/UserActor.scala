package org.flocka.Services.User

import akka.actor._
import akka.persistence._
import UserMsg._

case class UserState(active: Boolean,
                     credit: Long) {

  def updated(event: Event): UserState = event match {
    case UserCreated(userId) =>
      copy(active = true, 0)

    case UserDeleted(userId, true) =>
      copy(active = false, credit = credit)

    case CreditAdded(userId, amount, true) =>
      copy(active = false, credit = credit - amount)

    case CreditSubtracted(userId, amount, true) =>
      copy(active = false, credit = credit - amount)
  }
}

class UserActor(userId: Long) extends PersistentActor{
  override def persistenceId = userId.toString

  var state = UserState(false, 0)

  def updateState(event: Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: Event                             => updateState(evt)
    case SnapshotOffer(_, snapshot: UserState) => state = snapshot
  }

  val snapShotInterval = 1000
  val receiveCommand: Receive = {
    case CreateUser() =>
      //how to check if successful?
      persist(UserCreated(userId)) { event =>
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
      persist(CreditAdded(userId, amount, true)) { event =>
        updateState(event)
        sender() ! event
      }

    case SubtractCredit(userId, amount) =>
      //how to check if successful?
      persist(CreditSubtracted(userId, amount, true)) { event =>
        updateState(event)
        sender() ! event
      }
  }
}
