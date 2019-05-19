package org.flocka.sharding

import akka.actor.{Props, _}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}
import org.flocka.MessageTypes
import org.flocka.MessageTypes.{Command, Query}
import org.flocka.sharding.UserActor.UserActorTimeoutException
import org.flocka.sharding.UserServiceComs._

import scala.concurrent.duration._

/*
Hold the current state of the user here.
UserId matches the persistenceId and is the unique identifier for user and actor.
active identifies if the user is still an active user, or needs to be deleted
credit is the current credit of this user
 */
case class UserState(userId: Long,
                     active: Boolean,
                     credit: Long) {

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

object UserActor {
  def props(): Props = Props(new UserActor())

  case class InvalidUserException(userId: String) extends Exception("This user: " + userId + " is not active.")

  case class UserActorTimeoutException(userId: String) extends Exception(userId)

  import com.typesafe.config.Config
  import com.typesafe.config.ConfigFactory

  val conf: Config = ConfigFactory.load()

  val passivateTimeout = conf.getInt("user.passivate-timeout") seconds

  val snapShotInterval = conf.getInt("user.snapshot-interval")

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: Command =>
      (cmd.objectId.toString, cmd)
    case qry: Query =>
      (qry.objectId.toString, qry)

  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: Command =>
      (cmd.objectId % conf.getInt("user.numshards")).toString
    case qry: Query =>
      (qry.objectId % conf.getInt("user.numshards")).toString
  }

  val shardName: String = "User"
}

class UserActor() extends PersistentActor {
  override def persistenceId = self.path.name

  // Since we have millions of users, we should passivate quickly
  context.setReceiveTimeout(UserActor.passivateTimeout)


  var state = UserState(persistenceId.toLong, false, 0)

  def updateState(event: MessageTypes.Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: MessageTypes.Event => updateState(evt)
    case SnapshotOffer(_, snapshot: UserState) => state = snapshot
  }

  def queryHandler(query: MessageTypes.Query, userId: Long, event: MessageTypes.Event): Unit = {
    //ToDo: Check if we can assume that the message always arrives at the correct user actor
    try {
      if (validateState(query))
        sender() ! event
    } catch {
      case userException: UserActor.InvalidUserException => sender() ! akka.actor.Status.Failure(userException)
      case ex: Exception => throw ex
    }
  }

  def commandHandler(command: Command, userId: Long, event: MessageTypes.Event): Unit = {
    try {
      if (validateState(command)) persist(event) { event =>
        updateState(event)
        sender() ! event

        context.system.eventStream.publish(event) //TODO prettty sure this is unnecessary
        if (lastSequenceNr % UserActor.snapShotInterval == 0 && lastSequenceNr != 0)
          saveSnapshot(state)

        //publish on event stream? https://doc.akka.io/api/akka/current/akka/event/EventStream.html
      }

    } catch {
      case userException: UserActor.InvalidUserException => sender() ! akka.actor.Status.Failure(userException)
      case ex: Exception => throw ex
    }
  }

  /*
  Validate if the user actor state allows any interaction at the current point
   */
  def validateState(command: Command): Boolean = {
    command match {
      case CreateUser(_) =>
        if (state.active) return false
        else return true
      case _ =>
        if (state.active) return true
        else return false // throw new UserActor.InvalidUserException(state.userId.toString)
    }

  }


  def validateState(query: MessageTypes.Query): Boolean = {
    if (state.active) {
      return true
    }

    throw new UserActor.InvalidUserException(state.userId.toString)
  }

  //TODO figure out good interval value
  val receiveCommand: Receive = {
    case command@CreateUser(userId) =>
      commandHandler(command, userId, UserCreated(userId))

    case command@DeleteUser(userId) =>
      commandHandler(command, userId, UserDeleted(state.userId, true))

    case query@FindUser(userId) =>
      queryHandler(query, userId, (UserFound(userId, Set(userId, state.credit))))

    case query@GetCredit(userId) =>
      queryHandler(query, userId, (CreditGot(userId, state.credit)))

    case command@AddCredit(userId, amount) =>
      commandHandler(command, userId, CreditAdded(userId, amount, true))

    case command@SubtractCredit(userId, amount) =>
      commandHandler(command, userId, CreditSubtracted(userId, amount, true))

    case ReceiveTimeout =>
      System.out.println("I AM COMMITTING SEPUKO")
      context.parent ! Passivate(stopMessage = PoisonPill)
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