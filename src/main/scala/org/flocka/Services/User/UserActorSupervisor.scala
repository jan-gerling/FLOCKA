package org.flocka.Services.User

import java.util.UUID.randomUUID
import akka.pattern.ask
import akka.pattern.pipe
import UserCommunication._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.server.Directives.{complete, onSuccess}
import akka.persistence.{PersistentActor, SnapshotOffer}


case class SupervisorState(persistentUserActors: mutable.ListBuffer[Long]) {
  def updated(event: Event): SupervisorState = event match {
    case UserActorCreated(userID) =>
      copy(persistentUserActors += userID)
  }

  def size: Int = persistentUserActors.size
}

object UserActorSupervisor{
  def props(): Props = Props(new UserActorSupervisor())
}

class UserActorSupervisor() extends PersistentActor {

  override def persistenceId = self.path.name

  var state = SupervisorState(mutable.ListBuffer())

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
  var knownUserActor = mutable.Map.empty[Long, ActorRef]

  /*
  Use pipe pattern to forward the actual command to the correct actor and then relay it to the asking actor.
  */
  def commandHandler(command: UserCommunication.Command, userId: Long, sender: ActorRef): Future[Any] = {
    (actorHandler(userId) ? command) pipeTo sender
  }

  /*
  Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by userId
  ToDo: Find distributed and akka style implementation of UserRef lookup
   */
  def actorHandler(userId: Long): ActorRef = {
    knownUserActor.get(userId) match {
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
    return context.child(userId).getOrElse {
      return createUser(userId)
    }
  }

  def createUser(userId: String): ActorRef ={
    return context.actorOf(UserActor.props(), userId)
  }

  def generateUserId(): Long = {
    return Math.abs(randomUUID().getLeastSignificantBits)
  }

  //TODO figure out good interval value
  val snapShotInterval = 1000
  val receiveCommand: Receive = {
    case CreateUser() =>

      val userId: Long = generateUserId()
      val actorRef = createUser(userId.toString)
      persist(UserActorCreated(userId)) { event =>
        updateState(event)
        commandHandler(CreateUser(), userId, sender())
      }
    case DeleteUser(userId) =>
      commandHandler(DeleteUser(userId), userId, sender())
    case FindUser(userId) =>
      commandHandler(FindUser(userId), userId, sender())
    case GetCredit(userId) =>
      commandHandler(GetCredit(userId), userId, sender())
    case AddCredit(userId, amount) =>
      commandHandler(AddCredit(userId, amount), userId, sender())
    case SubtractCredit(userId, amount) =>
      commandHandler(SubtractCredit(userId, amount), userId, sender())

    case command @ _  => throw new IllegalArgumentException(command.toString)
  }
}
