package org.flocka.Services.User

import akka.actor.{ActorRef, ActorSelection, ActorSystem, Identify}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.pattern.{ask}
import UserCommunication._
import akka.util.Timeout
import akka.actor.{ActorRef, Props}


import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//TODO maybe extend HttpApp
object UserService extends App {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("FLOCKA")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val service = "users"
    val timeoutTime = 500 millisecond;
    val supervisorRef: ActorRef = system.actorOf(UserActorSupervisor.props(), 1.toString)

    /*
    Handles the given command for a UserActor by sending it with the ask pattern to the correct actor.
    Returns actually a future of type UserCommunication.Event.
    Giving userId -1 is no userId
    */
    def commandHandler(command: UserCommunication.Command, userId: Long): Future[Any] = {
      implicit val timeout = Timeout(timeoutTime)
      supervisorRef ? command
    }

    /*
    similar to the command handler
    */
    def queryHandler(command: UserCommunication.Query, userId: Long): Future[Any] = {
      implicit val timeout = Timeout(timeoutTime)
      supervisorRef ? command
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