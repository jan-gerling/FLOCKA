package org.flocka.Services.Stock

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics.{CommandHandler, MessageTypes, QueryHandler}
import org.flocka.Services.Stock.StockServiceComs._
import org.flocka.Services.User.IdManager

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Contains routes for the Rest Stock Service. Method bind is used to start the server.
  */
object StockService extends CommandHandler with QueryHandler {

  val randomGenerator: scala.util.Random = scala.util.Random
  val service = "stock"
  val timeoutTime: FiniteDuration = 500 milliseconds
  implicit val timeout: Timeout = Timeout(timeoutTime)

  /**
    * Starts the server
    * @param shardRegion the region behind which the
    * @param exposedPort the port in which to expose the service
    * @param executor jeez idk,
    * @param system the ActorSystem
    * @return
    */
 def bind(shardRegion: ActorRef, exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem) : Future[ServerBinding] = {

   /*
    Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
    Giving itemId -1 is no itemId, only for creating new stock items
    */
   def commandHandler(command: MessageTypes.Command): Future[Any] = {
     super.commandHandler(command, Option(shardRegion), timeoutTime, executor)
   }

   /*
    similar to the command handler
    */
   def queryHandler(query: MessageTypes.Query): Future [Any] = {
     super.queryHandler(query, Option(shardRegion), timeoutTime, executor)
   }

   val postCreateItemRoute: Route = {
     pathPrefix(service /  "item" / "create") {
       post {
         pathEndOrSingleSlash {
           onComplete(commandHandler(CreateItem(IdManager.generateId(StockSharding.numShards)))) {
             case Success(value) => complete(value.toString)
             case Failure(ex) => complete(s"An error occured: ${ex.getMessage}")
           }
         }
       }
     }
   }

    val getGetItemAvailabilityRoute: Route = {
      pathPrefix(service /  "availability" / LongNumber ) { itemId ⇒
        get{
          pathEndOrSingleSlash {
            onComplete(queryHandler(GetAvailability(itemId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occured: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postDecreaseItemAvailabilityRoute: Route = {
      pathPrefix(service /  "subtract" / LongNumber / LongNumber ) { ( itemId, amount) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(DecreaseAvailability(itemId, amount))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occured: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postIncreaseItemAvailabilityRoute: Route = {
      pathPrefix(service /  "add" / LongNumber / LongNumber) { ( itemId, amount) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(IncreaseAvailability(itemId, amount))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occured: ${ex.getMessage}")
            }               }
        }
      }
    }



    def route : Route = getGetItemAvailabilityRoute ~  postDecreaseItemAvailabilityRoute ~
      postIncreaseItemAvailabilityRoute ~ postCreateItemRoute

   implicit val materializer = ActorMaterializer()
   Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}
