package org.flocka.Services.User

import akka.actor.{ActorRef, ActorSelection, ActorSystem, Identify}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.pattern.ask
import UserServiceComs._
import akka.util.Timeout
import akka.actor.{ActorRef, Props}
import org.flocka.MessageTypes

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//TODO maybe extend to HttpApp
object UserService extends App {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("FLOCKA")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val service = "users"
    val timeoutTime = 500 millisecond;
    val randomGenerator  = scala.util.Random
    implicit val timeout = Timeout(timeoutTime)

    /*
    Stores all the supervisor currently known to the service to increase actorRef lookups.
    */
    var knownSupervisorActor = mutable.Map.empty[Long, ActorRef]

    /*
    Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by id.
    ToDo: Find distributed implementation of actorRef lookups maybe delegating this already to temporary actors?
    Get the reference to an existing actor.
     */
    def findActor(userId: Long): Option[ActorRef] = {
      userId match {
        case -1 =>
          val newSupervisorId: Long = randomGenerator.nextInt(UserActorSupervisor.supervisorIdRange.toInt)
          Some(createSupervisor(newSupervisorId.toString))
        case _ =>
          val supervisorId: Long = UserActorSupervisor.extractSupervisorId(userId)
          knownSupervisorActor.get(supervisorId) match {
            case Some(actorRef) =>
              return Some(actorRef)
            case None => Some(createSupervisor(supervisorId.toString))
        }
      }
    }

    /*
    Create a new actor with the given userId.
    NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
     */
    def createSupervisor(supervisorId: String): ActorRef ={
      val userActor = system.actorOf(UserActorSupervisor.props(), supervisorId)
      knownSupervisorActor += supervisorId.toLong -> userActor
      return userActor
    }

    /*
   Recover a new actor with the given userId.
   NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
    */
    def recoverSupervisor(supervisorId: String): ActorRef ={
      return createSupervisor(supervisorId)
    }

    /*
    Handles the given command for a UserActor by sending it with the ask pattern to the correct actor.
    Returns actually a future of type UserCommunication.Event.
    Giving userId -1 is no userId, only for creating new users
    */
    def commandHandler(command: MessageTypes.Command, userId: Long): Future[Any] = {
      findActor(userId) match {
        case Some(actorRef: ActorRef) => actorRef ? command
        case None => throw new IllegalArgumentException(userId.toString)
      }
    }

    /*
    similar to the command handler
    */
    def queryHandler(query: MessageTypes.Query, userId: Long): Future[Any] = {
      findActor(userId) match {
        case Some(actorRef: ActorRef) => actorRef ? query
        case None => throw new IllegalArgumentException(userId.toString)
      }
    }

    val postCreateUserRoute: Route = {
      pathPrefix(service /  "create" ) {
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(CreateUser(), -1)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val deleteRemoveUserRoute: Route = {
      pathPrefix(service /  "remove" / LongNumber) { userId ⇒
        delete{
          pathEndOrSingleSlash {
            onComplete(commandHandler(DeleteUser(userId), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val getFindUserRoute: Route = {
      pathPrefix(service /  "find" / LongNumber) { userId ⇒
        get{
          pathEndOrSingleSlash {
            onComplete(queryHandler(FindUser(userId), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val getCreditRoute: Route = {
      pathPrefix(service /  "credit" / LongNumber) { userId ⇒
        get {
          pathEndOrSingleSlash {
            onComplete(queryHandler(GetCredit(userId), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postSubtractCreditRoute: Route = {
      pathPrefix(service /  "credit" / "subtract" / LongNumber / LongNumber) { (userId, amount) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(SubtractCredit(userId, amount), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postAddCreditRoute: Route = {
      pathPrefix(service /  "credit" / "add" / LongNumber / LongNumber) { (userId, amount) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(AddCredit(userId, amount), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    def route : Route = postCreateUserRoute ~  deleteRemoveUserRoute ~ getFindUserRoute ~ getCreditRoute ~
      postSubtractCreditRoute ~ postAddCreditRoute

    val host = "0.0.0.0"
    val port = 9000
    val bindingFuture = Http().bindAndHandle(route, host, port)

    bindingFuture.onComplete {
      case Success(serverBinding) => println(s"listening to ${serverBinding.localAddress}")
      case Failure(error) => println(s"error: ${error.getMessage}")
    }
  }
}