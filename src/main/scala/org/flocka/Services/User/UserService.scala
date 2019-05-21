package org.flocka.Services.User

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics.{CommandHandler, MessageTypes, QueryHandler}
import org.flocka.Services.User.UserServiceComs._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


/**
  * Contains routes for the Rest User Service. Method bind is used to start the server.
  */
object UserService extends CommandHandler with QueryHandler {

  val randomGenerator: scala.util.Random  = scala.util.Random
  val service = "users"
  val timeoutTime: FiniteDuration = 500 millisecond
  implicit val timeout: Timeout = Timeout(timeoutTime)

  /**
    * Starts the server
    * @param shardRegion the region behind which the
    * @param exposedPort the port in which to expose the service
    * @param executor jeez idk,
    * @param system the ActorSystem
    * @return
    */
  def bind(shardRegion: ActorRef, exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding] = {
    /*
      Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
      Giving userId -1 is no userId, only for creating new users
      */
    def commandHandler(command: MessageTypes.Command): Future[Any] = {
      super.commandHandler(command, Option(shardRegion), timeoutTime, executor)
    }

    /*
      similar to the command handler
      */
    def queryHandler(query: MessageTypes.Query): Future[Any] = {
      super.queryHandler(query, Option(shardRegion), timeoutTime, executor)
    }

    val postCreateUserRoute: Route = {
      pathPrefix(service / "create") {
        post {
          pathEndOrSingleSlash {
            //ToDO: fix number generation, because it is actually in range Long and should use UserIdManager.shardregion
            onComplete(commandHandler(CreateUser(IdManager.generateId(UserSharding.numShards)))) {
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
            onComplete(commandHandler(DeleteUser(userId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val getFindUserRoute: Route = {
      pathPrefix(service / "find" / LongNumber) { userId ⇒
        get {
          pathEndOrSingleSlash {
            onComplete(queryHandler(FindUser(userId))) {
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
            onComplete(queryHandler(GetCredit(userId))) {
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
            onComplete(commandHandler(SubtractCredit(userId, amount))) {
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
            onComplete(commandHandler(AddCredit(userId, amount))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    def route: Route = postCreateUserRoute ~ deleteRemoveUserRoute ~ getFindUserRoute ~ getCreditRoute ~
      postSubtractCreditRoute ~ postAddCreditRoute

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}