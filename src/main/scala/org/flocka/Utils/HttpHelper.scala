package org.flocka.Utils

import java.util.concurrent.TimeoutException
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import scala.concurrent.{ExecutionContext, Future}

object HttpHelper {
  def sendRequest(request: HttpRequest, numTries: Int = 0, maxTries: Int = 3)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[HttpResponse] = {
    return Http(system).singleRequest(request).recover{
      case exception: TimeoutException =>
        if(numTries >= maxTries){
          println("Sending request " + request.toString + " timed out.")
          return Future.failed(exception)
        }else {
          return sendRequest(request, numTries + 1)
        }
      case exception: Exception =>
        println("Sending request " + request.toString + " failed with " + exception.toString)
        return Future.failed(exception)
    }
  }
}