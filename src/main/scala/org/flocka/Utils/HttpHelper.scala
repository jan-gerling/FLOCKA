package org.flocka.Utils

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import scala.concurrent.{ExecutionContext, Future}

object HttpHelper {
  def sendRequest(request: HttpRequest)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[HttpResponse] = {
    return Http(system).singleRequest(request).recover{
      case _: akka.stream.StreamTcpException =>
        return Future.successful(HttpResponse(status = StatusCodes.ServiceUnavailable))
      case failure@ _ =>
        return Future.failed(failure)
    }
  }
}