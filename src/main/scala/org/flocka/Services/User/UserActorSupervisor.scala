package org.flocka.Services.User

import java.util.UUID.randomUUID
import akka.pattern.ask
import akka.pattern.pipe
import UserServiceComs._
import akka.actor.SupervisorStrategy.{Stop}
import akka.util.Timeout
import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{ActorRef, OneForOneStrategy, Props}
import akka.persistence.{PersistentActor, SnapshotOffer}
import org.flocka.Services.User.UserActor.UserActorTimeoutException

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

  final val rebuildSupervisorIdMask: Long = 0xFF00000000000000L
  final val supervisorIdMask: Long = 0x00000000000000FFL
  final val randomIdMask: Long = 0x00FFFFFFFFFFFFFFL
  final val randomIdBitLength = 56
  //-1 to avoid negative longs
  final val supervisorIdRange: Int = Math.pow(2, 64 - randomIdBitLength - 1).toInt

  final def extractSupervisorId(userId: Long): Long ={
    return (userId & rebuildSupervisorIdMask) >> randomIdBitLength
  }
}

class UserActorSupervisor() extends PersistentActor {

  override def persistenceId = self.path.name

  val receiveRecover: Receive = {
    case _ =>
  }

  implicit val ec: ExecutionContext = context.dispatcher

  /*
  Defines the service context for this actor, e.g. ../users/user-supervisor.
   */
  val service = "user-supervisor"

  /*
  ToDo: find a good timeout time
   */
  val timeoutTime = 400 millisecond;
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
  */
  def commandHandler(command: UserServiceComs.Command,
                     userId: Long,
                     recipientTo: ActorRef,
                     condition: Any => Boolean): Future[Any] = {
    actorHandler (userId) match {
      case Some (actorRef) =>
        val actorFuture = actorRef ? command
        //ToDo: how to handle unexpected/ unwanted results?
        //ToDo: how to handle exceptions? aside from supervisor strategies?
        actorFuture.filter (condition).recover {
          // When filter fails, it will have a java.util.NoSuchElementException
          case m: NoSuchElementException => 0
        }
        actorFuture pipeTo recipientTo
      case None =>
        throw new IllegalArgumentException ("Can't find an existing user for user id: " + userId)
    }
  }

  /*
Similar to the command handler
*/
  def queryHandler(query: UserServiceComs.Query, userId: Long, recipientTo: ActorRef, condition: Any => Boolean): Future[Any] = {
    actorHandler(userId) match {
      case Some(actorRef) =>
        val actorFuture = actorRef ? query
        //ToDo: how to handle unexpected/ unwanted results?
        //ToDo: how to handle exceptions? aside from supervisor strategies?
        actorFuture.filter(condition).recover {
          // When filter fails, it will have a java.util.NoSuchElementException
          case m: NoSuchElementException => 0
        }
        actorFuture pipeTo recipientTo
      case None =>
        throw new IllegalArgumentException("Can't find an existing user for user id: " + userId)
    }
  }

  /*
  Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by userId.
  ToDo: Find distributed implementation of actorRef lookups maybe delegating this already to temporary actors?
  */
  def actorHandler(userId: Long): Option[ActorRef] = {
    findActor(userId) match{
      case Some(actorRef: ActorRef) => return Some(actorRef)
      case None => Some(createUser(userId.toString))
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
        return Some(context.child(userId.toString)).getOrElse{
          return None
        }
    }
  }

  /*
  Create a new actor with the given userId.
  NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
   */
  def createUser(userId: String): ActorRef ={
    val userActor = context.actorOf(UserActor.props(), userId)
    knownUserActor += userId.toLong -> userActor
    return userActor
  }

  /*
 Recover a new actor with the given userId.
 NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
  */
  def recoverUserActorRef(userId: String): ActorRef ={
    return createUser(userId)
  }

  /*
  Generate a userId for a user, this userId is also used to address the correct actor via the actor path.
  DO NOT ALTER A userID after it was assigned.
  The user id consists of two components:
  1. supervisor part (first 8 bit) - equivalent to the supervisor id, used to identify a supervisor for a specific userid
  2. randomUserId part (last 56 bit) - unique for each user actor of this supervisor
   */
  def generateUserId(): Long = {
    val randomIdPart: Long = Math.abs(randomUUID().getMostSignificantBits) & UserActorSupervisor.randomIdMask
    val nameIdPart: Long = persistenceId.toLong & UserActorSupervisor.supervisorIdMask
    val adressableId: Long = (nameIdPart << 56) + randomIdPart
    return adressableId
  }

  /*
  ToDo: Learn more about the different strategies
  Default strategy for unmatched exceptions is escalation
  https://doc.akka.io/docs/akka/current/fault-tolerance.html#creating-a-supervisor-strategy
   */
  override def supervisorStrategy = OneForOneStrategy() {
    case ex: UserActor.InvalidUserException => Stop
    case ex: UserActorTimeoutException => knownUserActor -= ex.getMessage.toLong; Stop
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
          case UserServiceComs.UserCreated(resultId) => userId == resultId
          case _ => false
        }
      )
    case command @ DeleteUser(userId) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserServiceComs.UserDeleted(resultId, status) => userId == resultId && status
          case _ => false
        })
    case query @ FindUser(userId) =>
      queryHandler(
        query,
        userId,
        sender(),
        _ match {
          case UserServiceComs.UserFound(resultId, _) => userId == resultId
          case _ => false
        })
    case query @ GetCredit(userId) =>
      queryHandler(
        query,
        userId,
        sender(),
        _ match {
          case UserServiceComs.UserDeleted(resultId, _) => userId == resultId
          case _ => false
        })
    case command @ AddCredit(userId, amount) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserServiceComs.CreditAdded(resultId, resultAmount, _) => userId == resultId && resultAmount == amount
          case _ => false
        })
    case command @ SubtractCredit(userId, amount) =>
      commandHandler(
        command,
        userId,
        sender(),
        _ match {
          case UserServiceComs.CreditSubtracted(resultId, resultAmount, _) => userId == resultId && resultAmount == amount
          case _ => false
        })

    case command @ _  => throw new IllegalArgumentException(command.toString)
  }
}