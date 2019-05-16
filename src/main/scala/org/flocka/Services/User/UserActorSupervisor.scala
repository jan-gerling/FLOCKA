package org.flocka.Services.User

import java.util.UUID.randomUUID
import akka.pattern.{ask}
import UserCommunication._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.pipe
import akka.actor.{ActorRef, Props}
import akka.persistence.{PersistentActor, SnapshotOffer}


case class SupervisorState(persistentUserActorRefs: scala.collection.mutable.Map[Long, ActorRef]) {
  def updated(event: Event): SupervisorState = event match {
    case UserActorCreated(userID, actorRef) =>
      copy(persistentUserActorRefs += userID -> actorRef)
  }

  def size: Int = persistentUserActorRefs.size
}

object UserActorSupervisor{
  def props(): Props = Props(new UserActorSupervisor())
}

class UserActorSupervisor() extends PersistentActor {

  override def persistenceId = self.path.name

  var state = SupervisorState(mutable.Map.empty[Long, ActorRef])

  def updateState(event: Event): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case event: Event => updateState(event)
    case SnapshotOffer(_, snapshot: SupervisorState) => state = snapshot
  }

  implicit val ec: ExecutionContext = context.dispatcher

  val service = "user-supervisor"
  val timeoutTime = 1000 millisecond;
  implicit val timeout = Timeout(timeoutTime)

  /*
  Use pipe pattern to forward the actual command to the correct actor and then relay it to the asking actor.
  */
  def commandHandler(command: UserCommunication.Command, userId: Long, sender: ActorRef): Future[Any] = {
    (actorHandler(userId) ? command) pipeTo (sender)
  }

  /*
  Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by userId
  ToDo: Find distributed and akka style implementation of UserRef lookup
   */
  def actorHandler(userId: Long): ActorRef = {
    state.persistentUserActorRefs.get(userId) match {
      case Some(actorRef) =>
        return actorRef
      case None =>
        return getChild(userId.toString);
    }
  }

  /*
  Get the child actor of this supervisor with the child id
  */
  def getChild(userId: String): ActorRef = {
    val actorRef = context.child(userId).getOrElse {
      throw new IllegalArgumentException("Cannot find user actor for user id: " + userId)
    }

    return actorRef
  }

  def createUser(userId: Long): ActorRef ={
    return context.actorOf(UserActor.props(), userId.toString)
  }

  def generateUserId(): Long = {
    return Math.abs(randomUUID().getLeastSignificantBits)
  }

  //TODO figure out good interval value
  val snapShotInterval = 1000
  val receiveCommand: Receive = {
    case command @ CreateUser() =>

      val userId: Long = generateUserId()
      val actorRef = createUser(userId)
      persist(UserActorCreated(userId, actorRef)) { event =>
        updateState(event)
        println("Supervisor " + persistenceId + " has size: " + state.size)
        (actorRef ? command) pipeTo(sender())
      }

    case command @ DeleteUser(userId) => commandHandler(command, userId, sender())
    case command @ FindUser(userId) => commandHandler(command, userId, sender())
    case command @ GetCredit(userId) => commandHandler(command, userId, sender())
    case command @ AddCredit(userId, _) => commandHandler(command, userId, sender())
    case command @ SubtractCredit(userId, _) => commandHandler(command, userId, sender())

    case command @ _  => throw new IllegalArgumentException(command.toString)
  }
}
