package org.flocka.sharding

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.MessageTypes
import org.flocka.sharding.UserServiceComs._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Random, Success}

object UserService extends CommandHandler with QueryHandler {

  val randomGenerator = scala.util.Random
  val service = "users"
  val timeoutTime: FiniteDuration = 500 millisecond
  implicit val timeout: Timeout = Timeout(timeoutTime)

  val random: Random = Random

  def bind(decider: ActorRef, exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding] = {
    /*
      Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
      Giving userId -1 is no userId, only for creating new users
      */
    def commandHandler(command: MessageTypes.Command, objectId: Long): Future[Any] = {
      super.commandHandler(command, Option(decider), timeoutTime, executor)
    }

    /*
      similar to the command handler
      */
    def queryHandler(query: MessageTypes.Query, objectId: Long): Future[Any] = {
      super.queryHandler(query, Option(decider), timeoutTime, executor)
    }

    val postCreateUserRoute: Route = {
      pathPrefix(service / "create") {
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(CreateUser(random.nextLong()), -1)) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val deleteRemoveUserRoute: Route = {
      pathPrefix(service / "remove" / LongNumber) { userId ⇒
        delete {
          pathEndOrSingleSlash {
            onComplete(commandHandler(DeleteUser(userId), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val testRoute: Route = {
      pathPrefix("test") {
        get {
          pathEndOrSingleSlash {
            complete("hi")
          }
        }
      }
    }

    val getFindUserRoute: Route = {
      pathPrefix(service / "find" / LongNumber) { userId ⇒
        get {
          pathEndOrSingleSlash {
            onComplete(queryHandler(FindUser(userId), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val getCreditRoute: Route = {
      pathPrefix(service / "credit" / LongNumber) { userId ⇒
        get {
          pathEndOrSingleSlash {
            onComplete(queryHandler(GetCredit(userId), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postSubtractCreditRoute: Route = {
      pathPrefix(service / "credit" / "subtract" / LongNumber / LongNumber) { (userId, amount) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(SubtractCredit(userId, amount), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postAddCreditRoute: Route = {
      pathPrefix(service / "credit" / "add" / LongNumber / LongNumber) { (userId, amount) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(AddCredit(userId, amount), userId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    def route: Route = postCreateUserRoute ~ deleteRemoveUserRoute ~ getFindUserRoute ~ getCreditRoute ~
      postSubtractCreditRoute ~ postAddCreditRoute ~ testRoute

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}