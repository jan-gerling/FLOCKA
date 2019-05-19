package org.flocka.Services.User

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import UserServiceComs._
import akka.util.Timeout
import akka.actor.ActorRef
import org.flocka.MessageTypes
import org.flocka.ServiceBasics.{ServiceBase}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object UserService extends ServiceBase with UserIdManager{
  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("FLOCKA")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executor: ExecutionContext = system.dispatcher

    val randomGenerator  = scala.util.Random
    val service = "users"
    val timeoutTime: FiniteDuration = 500 millisecond
    implicit val timeout: Timeout = Timeout(timeoutTime)

    /*
    Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
    Giving userId -1 is no userId, only for creating new users
    */
    def commandHandler(command: MessageTypes.Command, userId: Long): Future[Any] = {
      super.commandHandler(command, getActor(userId), timeoutTime, executor)
    }

    /*
    similar to the command handler
    */
    def queryHandler(query: MessageTypes.Query, userId: Long): Future[Any] = {
      super.queryHandler(query, getActor(userId), timeoutTime, executor)
    }

    /*
    Get the actor reference for the supervisor for the given userid.
     */
    def getActor(userId: Long): Option[ActorRef] ={
      var supervisorId: Long = -1
      userId match {
        case -1 => supervisorId = randomGenerator.nextInt(supervisorIdRange)
        case _ => supervisorId = extractSupervisorId(userId)
      }
      return super.getActor(supervisorId.toString, system, UserActorSupervisor.props())
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