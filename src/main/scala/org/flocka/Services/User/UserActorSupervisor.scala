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
import akka.persistence.{PersistentActor, SnapshotOffer}

/*
!Currently this is not used!
This state saves all existing user actors related to this supervisor.
Use this state to validate the existence of user actors.
ToDO: figure out if this is necessary or could be done with some build in functionality
 */
case class SupervisorState(persistentUserActors: mutable.ListBuffer[Long]) {
  def updated(event: Event): SupervisorState = event match {
    case UserActorCreated(userID) =>
      copy(persistentUserActors += userID)
    case UserActorDeleted(userID) =>
      copy(persistentUserActors -= userID)
  }

  def size: Int = persistentUserActors.size
}

/*
This Object stores the props to create a UserActorSupervisor.
 */
object UserActorSupervisor{
  /*
  Props used to create a new UserActorSupervisor.
  "Props is a configuration class to specify options for the creation of actors."
  For more details on props look here: https://doc.akka.io/docs/akka/2.5.5/scala/actors.html
   */
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

  /*
  Defines the service context for this actor, e.g. ../users/user-supervisor.
   */
  val service = "user-supervisor"

  /*
  ToDo: find a good timeout time
   */
  val timeoutTime = 500 millisecond;
  implicit val timeout = Timeout(timeoutTime)

  /*
  Stores all the actors currently known to the supervisor to increase actorRef lookups.
   */
  var knownUserActor = mutable.Map.empty[Long, ActorRef]

  /*
  Use pipe pattern to forward the actual command to the correct actor and then relay it to the asking actor enforcing conditions.
  command: The command to send to the actor
  userId: userId of the addressed user, implicates the user actor
  recipientTo: The asking actor, often the user service, to this actor the response from the user actor is send to+
  condition: all conditions the response has to fulfill in order to be passed
  createNew: Do you expect to create a new UserActor, only to be set true from CreateUser Command
  */
  def commandHandler(command: UserCommunication.Command, userId: Long, recipientTo: ActorRef, condition: Any => Boolean, createNew: Boolean = false): Future[Any] = {
    actorHandler(userId, createNew) match {
      case Some(actorRef) =>
        val actorFuture = actorRef ? command
        //ToDo: how to handle unexpected/ unwanted results?
        actorFuture.filter(condition).recover {
          // When filter fails, it will have a java.util.NoSuchElementException
          case m: NoSuchElementException => 0
        }
        actorFuture pipeTo recipientTo
      case None =>
        if (createNew == false) {throw new IllegalArgumentException("Can't find an existing user for user id: " + userId)}
        else {throw new IllegalArgumentException("Can't create a new user for user id: " + userId)}
    }
  }

  /*
  Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by userId.
  Create new specifies whether this user is supposed to be created newly.
  ToDo: Find distributed implementation of actorRef lookups maybe delegating this already to temporary actors?
  */
  def actorHandler(userId: Long, createNew: Boolean): Option[ActorRef] = {
    (createNew, findActor(userId)) match{
      case (false, Some(actorRef: ActorRef)) => return Some(actorRef)
      case (true, Some(_)) => throw  new IllegalAccessException("You want to create a new user with id that already exists: " + userId)
      case (false, None) => return None
      case (true, None) => Some(createUser(userId.toString))
    }
  }

  /*
  Get the reference to an existing actor.
   */
  def findActor(userId: Long): Option[ActorRef] = {
    knownUserActor.get(userId) match {
      case Some(actorRef) =>
        return Some(actorRef)
      case None =>
        if(state.persistentUserActors.contains(userId)){
          return Some(recoverUserActor(userId.toString))
        }
        else
          return Some(createUser(userId.toString))
    }
  }

  /*
  Create a new actor with the given userId.
  NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
   */
  def createUser(userId: String): ActorRef ={
    return context.actorOf(UserActor.props(), userId)
  }

  /*
 Recover a new actor with the given userId.
 NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
  */
  def recoverUserActor(userId: String): ActorRef ={
    return context.actorOf(UserActor.props(), userId)
  }

  /*
  Generate a userId for a user, this userId is also used to address the correct actor via the actor path.
  DO NOT ALTER A userID after it was assigned.
   */
  def generateUserId(): Long = {
    return Math.abs(randomUUID().getLeastSignificantBits)
  }

  //TODO figure out good interval value for snapshots
  val snapShotInterval = 1000

  /*
  Receive the commands here and process them, to keep the communication running.
   */
  val receiveCommand: Receive = {
    case command @ CreateUser() =>
      val userId = generateUserId()
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserCommunication.UserCreated(resultId) => userId == resultId
          case _ => false
        },
        true
      )
    case command @ DeleteUser(userId) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserCommunication.UserDeleted(resultId, status) => userId == resultId && status
          case _ => false
        })
    case command @ FindUser(userId) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserCommunication.UserFound(resultId, _) => userId == resultId
          case _ => false
        })
    case command @ GetCredit(userId) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserCommunication.UserDeleted(resultId, _) => userId == resultId
          case _ => false
        })
    case command @ AddCredit(userId, amount) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserCommunication.CreditAdded(resultId, resultAmount, _) => userId == resultId && resultAmount == amount
          case _ => false
        })
    case command @ SubtractCredit(userId, amount) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserCommunication.CreditSubtracted(resultId, resultAmount, _) => userId == resultId && resultAmount == amount
          case _ => false
        })

    case command @ _  => throw new IllegalArgumentException(command.toString)
  }
}