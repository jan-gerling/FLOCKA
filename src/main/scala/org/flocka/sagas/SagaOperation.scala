package org.flocka.sagas

import java.net.URI
import java.util.UUID.randomUUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import scala.concurrent.{ExecutionContext, Future}

object SagaOperationState extends Enumeration {
  val PENDING, SUCCESS, FAILURE, TIMEOUT = Value
}

//ToDo make this an actor
case class SagaOperation(pathForward: URI, pathInvert: URI, forwardSuccessfulCondition: HttpResponse => Boolean){
  lazy val forwardId: Long = randomUUID().toString.hashCode.toLong
  lazy val backwardId: Long = randomUUID().toString.hashCode.toLong

  var resultState = SagaOperationState.PENDING

  def isCompleted: Boolean = {
    return resultState == SagaOperationState.TIMEOUT || resultState == SagaOperationState.SUCCESS || resultState == SagaOperationState.FAILURE
  }

  def doForward()(implicit executor: ExecutionContext, system: ActorSystem): Future[Boolean] = {
    doOperation(forwardId, pathForward, forwardSuccessfulCondition)
    return doOperation(forwardId, pathForward, forwardSuccessfulCondition)
  }

  def doInvert()(implicit executor: ExecutionContext, system: ActorSystem): Future[Boolean] = {
    return doOperation(backwardId, pathInvert, _ => true)
  }

  private def doOperation(operationId: Long, path: URI, conditions: HttpResponse => Boolean)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[Boolean] ={
    println("doOperation for id: " + operationId + " does " + path + "\nCurrent state for operation: " + resultState)
    val finalUri: String = buildPath(operationId, path)

    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(5 seconds)
    val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    val responseFuture: Future[HttpResponse] = sendRequest(finalUri, connectionPoolSettings)
    return responseFuture.map(
      conditions(_) match {
        case true   =>
          if(resultState != SagaOperationState.PENDING)
            resultState = SagaOperationState.SUCCESS
          true
        case false  =>
          if(resultState != SagaOperationState.PENDING)
            resultState = SagaOperationState.FAILURE
          false
      }
    )
  }

  private def buildPath(operationId: Long, path: URI): String ={
    return path.toString + "/" + operationId.toString
  }

  private def sendRequest(path: String, connectionPoolSettings: ConnectionPoolSettings, numTries: Int = 0)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[HttpResponse] = {
    return Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = path.toString), settings = connectionPoolSettings).recover{
      case exception: TimeoutException =>
        if(numTries >= SagasExecutionControllerActor.MAX_NUM_TRIES){
          resultState = SagaOperationState.TIMEOUT
          return Future.failed(exception)
        } else {
          return sendRequest(path, connectionPoolSettings, numTries + 1)
        }
    }
  }
}