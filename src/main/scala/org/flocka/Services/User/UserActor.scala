package org.flocka.Services.User

import akka.actor._
import UserCommunication._
import akka.actor.Props
import akka.persistence.{PersistentActor, SnapshotOffer}

/*
Hold the current state of the user here.
UserId matches the persistenceId and is the unique identifier for user and actor.
active identifies if the user is still an active user, or needs to be deleted
credit is the current credit of this user
 */
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

    case _ => throw new IllegalArgumentException(event.toString + "is not a valid event for UserActor.")
  }
}

object UserActor{
  def props(): Props = Props(new UserActor())
}

class UserActor() extends PersistentActor{
  override def persistenceId = self.path.name

  var state = UserState(persistenceId.toLong, false, 0)

  def updateState(event: Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: Event                             => updateState(evt)
    case SnapshotOffer(_, snapshot: UserState) => state = snapshot
  }

  def queryHandler(query: UserCommunication.Query, userId: Long, event: UserCommunication.Event ): Unit = {
    //ToDo: Check if we can assume that the message always arrives at the correct user actor
    if(validateState())
      sender() ! event
  }

  def commandHandler(command: UserCommunication.Command, userId: Long, event: UserCommunication.Event ): Unit = {
    if(command == UserCommunication.CreateUser() || validateState()) persist(event) { event =>
      updateState(event)
      sender() ! event

      context.system.eventStream.publish(event)
      if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
        saveSnapshot(state)

      //publish on event stream? https://doc.akka.io/api/akka/current/akka/event/EventStream.html
    }
  }

  /*
  Validate if the user actor state allows any interaction at the current point
   */
  def validateState(): Boolean ={
    if(state.active) {
      return  true
    }
    throw new IllegalAccessError("This user: " + state.userId + " is not active.")
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
  }
}