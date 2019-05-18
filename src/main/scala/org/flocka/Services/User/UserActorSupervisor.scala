package org.flocka.Services.User

import java.util.UUID.randomUUID
import UserServiceComs._
import akka.actor.SupervisorStrategy.Stop
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{ActorRef, OneForOneStrategy, Props}
import akka.persistence.PersistentActor
import org.flocka.MessageTypes
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
}

class UserActorSupervisor() extends PersistentActor with ActorLookup with CommandHandler with QueryHandler with UserIdManager{

  override def persistenceId = self.path.name

  val receiveRecover: Receive = {
    case _ =>
  }

  implicit val executor: ExecutionContext = context.dispatcher

  /*
  ToDo: Learn more about the different strategies
  Default strategy for unmatched exceptions is escalation
  https://doc.akka.io/docs/akka/current/fault-tolerance.html#creating-a-supervisor-strategy
   */
  override def supervisorStrategy = OneForOneStrategy() {
    case ex: UserActor.InvalidUserException => Stop
    case ex: UserActorTimeoutException => Stop
  }

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
  Use pipe pattern to forward the actual command to the correct actor and then relay it to the asking actor enforcing conditions.
  command: The command to send to the actor
  userId: userId of the addressed user, implicates the user actor
  recipientTo: The asking actor, often the user service, to this actor the response from the user actor is send to+
  condition: all conditions the response has to fulfill in order to be passed
  */
  def commandHandler(command: MessageTypes.Command,
                   userId: Long,
                   recipientTo: ActorRef,
                   postConditions: Any => Boolean): Future[Any] = {
    super.commandHandler(command, getActor(userId), timeoutTime, executor, Some(recipientTo), postConditions)
  }

  /*
 Similar to the command handler
 */
  def queryHandler(query: MessageTypes.Query,
                   userId: Long,
                   recipientTo: ActorRef,
                   postConditions: Any => Boolean): Future[Any] = {
    super.queryHandler(query, getActor(userId), timeoutTime, executor, Some(recipientTo), postConditions)
  }

  /*
  Get the actor reference for the supervisor for the given userid.
   */
  def getActor(userId: Long): Option[ActorRef] ={
    val actorId: Long = extractUserActorId(userId)
    return super.getActor(actorId.toString, context, UserActor.props())
  }

  /*
  Generate a userId for a user, this userId is also used to address the correct actor via the actor path.
  DO NOT ALTER A userID after it was assigned.
  The user id consists of two components:
  1. supervisor part (first 12 bit) - equivalent to the supervisor id, used to identify a supervisor for a specific userid
  2. randomUserId part (last 56 bit) - unique for each user actor of this supervisor
   */
  def generateUserId(): Long = {
    val randomIdPart: Long = Math.abs(randomUUID().getMostSignificantBits) & randomIdMask
    val nameIdPart: Long = persistenceId.toLong & supervisorIdMask
    val adressableId: Long = (nameIdPart << randomIdBitLength) + randomIdPart

    return adressableId
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

  /*
  For Debugging only.
  override def preStart() = println("Supervisor: " + persistenceId + " at " + self.path + " was started.")
  override def postStop() = println("Supervisor: " + persistenceId + " at " + self.path + " was shut down.")
  override def preRestart(reason: Throwable, message: Option[Any]) = {
    println("Supervisor: " + persistenceId + " at " + self.path + " is restarting.")
    super.preRestart(reason, message)
  }
  override def postRestart(reason: Throwable) = {
    println("Supervisor: " + persistenceId + " at " + self.path + " has restarted.")
    super.postRestart(reason)
  }
  End Debugging only.
  */
}