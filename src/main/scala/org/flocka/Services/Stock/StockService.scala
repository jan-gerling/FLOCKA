package org.flocka.Services.Stock

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics._
import org.flocka.Services.Stock.StockServiceComs._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Contains routes for the Rest Stock Service. Method bind is used to start the server.
  */
object StockService extends ServiceBase {

  override val configName: String = "stock-service.conf"
  val randomGenerator: scala.util.Random = scala.util.Random
  val service = "stock"


 def bind(shardRegion: ActorRef)(implicit system: ActorSystem, executor: ExecutionContext) : Future[ServerBinding] = {
   val regionalIdManager: IdGenerator = new IdGenerator()

   /*
    Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
    Giving itemId -1 is no itemId, only for creating new stock items
    */
   def commandHandler(command: MessageTypes.Command): Future[Any] = {
     super.commandHandler(command, Option(shardRegion))
   }

   /*
    similar to the command handler
    */
   def queryHandler(query: MessageTypes.Query): Future [Any] = {
     super.queryHandler(query, Option(shardRegion))
   }

   def createNewItem(): Route ={
     onComplete(commandHandler(CreateItem(regionalIdManager.generateId(StockSharding.numShards)))) {
       case Success(value) => complete(value.toString)
       case Failure(ex) => if(ex.toString.contains("InvalidOperationException")){regionalIdManager.increaseEntropy() ;createNewItem()} else complete(s"An error occurred: ${ex.getMessage}")
     }
   }

   val postCreateItemRoute: Route = {
     pathPrefix(service /  "item" / "create") {
       post {
         pathEndOrSingleSlash {
           createNewItem()
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
      pathPrefix(service /  "subtract" / LongNumber / LongNumber ~ Slash.? ~ LongNumber.?) { ( itemId, amount, operationId) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(DecreaseAvailability(itemId, amount, operationId.getOrElse {-1L}))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occured: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postIncreaseItemAvailabilityRoute: Route = {
      pathPrefix(service /  "add" / LongNumber / LongNumber ~ Slash.? ~ LongNumber.?) { ( itemId, amount, operationId) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(IncreaseAvailability(itemId, amount, operationId.getOrElse{-1L}))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occured: ${ex.getMessage}")
            }
          }
        }
      }
    }



    def route : Route = getGetItemAvailabilityRoute ~  postDecreaseItemAvailabilityRoute ~
      postIncreaseItemAvailabilityRoute ~ postCreateItemRoute

   implicit val materializer = ActorMaterializer()
   Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}
