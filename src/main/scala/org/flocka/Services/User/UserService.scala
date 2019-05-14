package org.flocka.Services.User

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import UserMsg._
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


    def createUser() : Future[Any] = {
      implicit val timeout = Timeout(5 seconds)
      val actorRef = system.actorOf(UserMsg.props());
      actorRef ? CreateUser();
    }

    val postCreateUserRoute: Route = {
      pathPrefix(service /  "create" ) {
        post{
          pathEndOrSingleSlash {
            onSuccess(createUser()) { userId => complete("User created:")
            }
          }
        }
      }
    }

    val deleteRemoveUserRoute: Route = {
      pathPrefix(service /  "remove" / LongNumber) { id ⇒
        delete{
          pathEndOrSingleSlash {
            complete("Remove User " + id)
          }
        }
      }
    }

    val getFindUserRoute: Route = {
      pathPrefix(service /  "find" / LongNumber) { id ⇒
        get{
          pathEndOrSingleSlash {
            complete("Find User " + id)
          }
        }
      }
    }

    val getCreditRoute: Route = {
      pathPrefix(service /  "credit" / LongNumber) { id ⇒
        get {
          pathEndOrSingleSlash {
            complete("Get Credit " + id)
          }
        }
      }
    }

    val postSubtractCreditRoute: Route = {
      pathPrefix(service /  "credit" / "subtract" / LongNumber / LongNumber) { (id, amount) ⇒
        post {
          pathEndOrSingleSlash {
            complete("Subtract Credit " + amount + " from " + id)
          }
        }
      }
    }

    val postAddCreditRoute: Route = {
      pathPrefix(service /  "credit" / "add" / LongNumber / LongNumber) { (id, amount) ⇒
        post {
          pathEndOrSingleSlash {
            complete("Add Credit " + amount + " to " + id)
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
