package org.flocka

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

//TODO maybe extend HttpApp
object OrderService extends App {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("Flocka")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val service = "orders"

    val postCreateOrderRoute: Route = {
      pathPrefix(service /  "create" / LongNumber ) { orderId ⇒
        post{
          pathEndOrSingleSlash {
            complete("Create Order " + orderId)
          }
        }
      }
    }

    val deleteRemoveOrderRoute: Route = {
      pathPrefix(service /  "remove" / LongNumber) { orderId ⇒
        delete{
          pathEndOrSingleSlash {
            complete("Remove Order " + orderId)
          }
        }
      }
    }

    val getFindOrderRoute: Route = {
      pathPrefix(service /  "find" / LongNumber) { orderId ⇒
        get{
          pathEndOrSingleSlash {
            complete("Find Order " + orderId)
          }
        }
      }
    }

    val postAddItemRoute: Route = {
      pathPrefix(service /  "addItem" / LongNumber / LongNumber) { (orderId, itemId) ⇒
        post{
          pathEndOrSingleSlash {
            complete("Post Item " + itemId + " for order " + orderId)
          }
        }
      }
    }

    val deleteRemoveItemRoute: Route = {
      pathPrefix(service /  "removeItem" / LongNumber / LongNumber) { (orderId, itemId) ⇒
        delete{
          pathEndOrSingleSlash {
            complete("Delete Item " + itemId + " from order " + orderId)
          }
        }
      }
    }

    val postCheckoutOrderRoute: Route = {
      pathPrefix(service /  "checkout" /  LongNumber) { orderId ⇒
        post{
          pathEndOrSingleSlash {
            complete("Post for Checking Out Order " + orderId)
          }
        }
      }
    }


    def route : Route = postCreateOrderRoute ~ deleteRemoveOrderRoute ~ getFindOrderRoute ~
                        postAddItemRoute ~ deleteRemoveItemRoute ~ postCheckoutOrderRoute

    val host = "0.0.0.0"
    val port = 9000
    val bindingFuture = Http().bindAndHandle(route, host, port)

    bindingFuture.onComplete {
      case Success(serverBinding) => println(s"listening to ${serverBinding.localAddress}")
      case Failure(error) => println(s"error: ${error.getMessage}")
    }
  }
}