package org.flocka.Services.User

import akka.actor.{ActorRef, ActorSelection, ActorSystem, Identify}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import UserCommunication._
import akka.pattern.ask
import akka.actor.Props
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//TODO maybe extend HttpApp
object UserService extends App {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("Flocka")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val service = "users"
    val timeoutTime = 250 millisecond;

    /*
    ToDo: Find distributed and akka style implementation of UserRef lookup
     */
    var persistentUserActor = scala.collection.mutable.Map[Long, ActorRef]()

    /*
    Handles the given command for a UserActor by sending it with the ask pattern to the correct actor.
    Returns actually a future of type UserCommunication.Event.
    Giving userId -1 is no userId
    */
    def commandHandler(command: UserCommunication.Command, userId: Long): Future[Any] = {
      implicit val timeout = Timeout(timeoutTime)
      actorHandler(userId) ? command
    }

    /*
    Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by userId
    ToDo: Find distributed and akka style implementation of UserRef lookup
     */
    def actorHandler(userId: Long) : ActorRef = {
      (userId, persistentUserActor.get(userId)) match {
        case (-1, _) =>
          return system.actorOf(UserCommunication.props())
        case (_, Some(actorRef)) =>
          return actorRef
        case (_, None) =>
          return system.actorOf(UserCommunication.props())
      }
    }

    val postCreateUserRoute: Route = {
      pathPrefix(service /  "create" ) {
        post{
          pathEndOrSingleSlash {
            onSuccess(commandHandler(CreateUser(), -1)) {
              case UserCommunication.UserCreated(userId) => complete("User: " + userId + " was created.")
              case _ => throw new Exception("A UserCreated event was expected, but a ")
            }
          }
        }
      }
    }

    val deleteRemoveUserRoute: Route = {
      pathPrefix(service /  "remove" / LongNumber) { userId ⇒
        delete{
          pathEndOrSingleSlash {
            pathEndOrSingleSlash {
              onSuccess(commandHandler(DeleteUser(userId), userId)) {
                case UserCommunication.UserDeleted(userId, status) => complete("User: " + userId + " was deleted: " + status)
                case _ => throw new Exception("A UserDeleted event was expected, but a ")
              }
            }
          }
        }
      }
    }

    val getFindUserRoute: Route = {
      pathPrefix(service /  "find" / LongNumber) { userId ⇒
        get{
          pathEndOrSingleSlash {
            onSuccess(commandHandler(FindUser(userId), userId)) {
              case UserCommunication.UserFound(userId, data) => complete("User: " + userId + " has: " + data.toString)
              case _ => throw new Exception("A UserFound event was expected, but a ")
            }
          }
        }
      }
    }

    val getCreditRoute: Route = {
      pathPrefix(service /  "credit" / LongNumber) { userId ⇒
        get {
          pathEndOrSingleSlash {
            onSuccess(commandHandler(GetCredit(userId), userId)) {
              case UserCommunication.CreditGot(userId, credit) => complete("User: " + userId + " has: " + credit)
              case _ => throw new Exception("A CreditGot event was expected, but a ")
            }
          }
        }
      }
    }

    val postSubtractCreditRoute: Route = {
      pathPrefix(service /  "credit" / "subtract" / LongNumber / LongNumber) { (userId, amount) ⇒
        post {
          pathEndOrSingleSlash {
            onSuccess(commandHandler(SubtractCredit(userId, amount), userId)) {
              case UserCommunication.CreditSubtracted(userId, amount, credit) => complete("User: " + userId + " credit was subtracted by " + amount + " to "+ credit)
              case _ => throw new Exception("A CreditSubtracted event was expected, but a ")
            }
          }
        }
      }
    }

    val postAddCreditRoute: Route = {
      pathPrefix(service /  "credit" / "add" / LongNumber / LongNumber) { (userId, amount) ⇒
        post {
          pathEndOrSingleSlash {
            onSuccess(commandHandler(AddCredit(userId, amount), userId)) {
              case UserCommunication.CreditAdded(userId, amount, credit) => complete("User: " + userId + " credit was increased by " + amount + " to "+ credit)
              case _ => throw new Exception("A CreditAdded event was expected, but a ")
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
