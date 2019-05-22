package org.flocka.Services.Stock

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{complete, get, pathEndOrSingleSlash, pathPrefix, post}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

//TODO maybe extend HttpApp
object StockService extends App {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("Flocka")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val service = "stock"

    val getGetItemAvailabilityRoute: Route = {
      pathPrefix(service /  "availability" / LongNumber ) { itemId ⇒
        get{
          pathEndOrSingleSlash {
            complete("Get Item " + itemId + " Availability")
          }
        }
      }
    }

    val postDecreaseItemAvailabilityRoute: Route = {
      pathPrefix(service /  "subtract" / LongNumber / LongNumber ) { ( itemId, number) ⇒
        post{
          pathEndOrSingleSlash {
            complete("Decrease Item " + itemId + " Availability By " + number + " Units")
          }
        }
      }
    }

    val postIncreaseItemAvailabilityRoute: Route = {
      pathPrefix(service /  "add" / LongNumber / LongNumber) { ( itemId, number) ⇒
        post{
          pathEndOrSingleSlash {
            complete("Decrease Item " + itemId + " Availability By " + number + " Units")
          }
        }
      }
    }

    val postCreateItemRoute: Route = {
      pathPrefix(service /  "item" / "create") {
        post {
          pathEndOrSingleSlash {
            complete("Created Item ")
          }
        }
      }
    }

    def route : Route = getGetItemAvailabilityRoute ~  postDecreaseItemAvailabilityRoute ~ postIncreaseItemAvailabilityRoute ~
      postCreateItemRoute

    val host = "0.0.0.0"
    val port = 9000
    val bindingFuture = Http().bindAndHandle(route, host, port)

    bindingFuture.onComplete {
      case Success(serverBinding) => println(s"listening to ${serverBinding.localAddress}")
      case Failure(error) => println(s"error: ${error.getMessage}")
    }
  }
}
