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

object ResultState extends Enumeration {
  val SUCCESS, FAILURE, TIMEOUT, NONE = Value
}

object OperationState extends Enumeration {
  val PENDING, IDLE, DONE = Value
}

/**
  * Single operation entity which can be reverted and will be executed idempotent.
  * @param pathForward fully qualified URI to a service, must be extendable with an operation id
  * @param pathRevert fully qualified URI to a service reverting the forward path changes, must be extendable with an operation id
  * @param forwardCondition defines expectations for the response of the forward path operation
  * @param bestEffortReverse if reverse operation fails still report a success, otherwise escalate an exception
  * @param baseTimeout default amount of time to wait for the operation -- having this configurable per operation is a good idea
  * @param timeoutScaling amount of extra time to wait every retry
  */
case class SagaOperation(pathForward: URI, pathRevert: URI, forwardCondition: String => Boolean, bestEffortReverse: Boolean = true, baseTimeout: Int = 1, timeoutScaling : Int = 1){
  lazy val forwardId: Long = Math.abs(randomUUID().toString.hashCode.toLong)
  lazy val reverseId: Long = Math.abs(randomUUID().toString.hashCode.toLong)

  /**
    * Result state of this Saga Operation, e.g. SUCCESS
    */
  var resultState = ResultState.NONE

  /**
    * Current execution state of this SagaOperation, e.g. IDLE
    */
  var executionState = OperationState.IDLE

  /**
    * @return was this SagaOperation already executed and finished?
    */
  def isCompleted: Boolean = {
    return executionState == OperationState.DONE
  }

  /**
    * @return was this SagaOperation already executed and finished successfully?
    */
  def isSuccess: Boolean = {
    return isCompleted && resultState == ResultState.SUCCESS
  }

  /**
    * @return was this SagaOperation already executed and failed?
    */
  def isFailure: Boolean = {
    return isCompleted && (resultState == ResultState.TIMEOUT || resultState == ResultState.FAILURE)
  }

  /**
    * @return is this SagaOperation currently being executed?
    */
  def isBusy: Boolean = {
    return executionState == OperationState.PENDING
  }

  /**
    * @return could this SagaOperation be executed in its current state
    */
  def isExecutable: Boolean = {
    return executionState == OperationState.IDLE
  }

  /**
    * Execute the forward path of this SagaOperation in an idem potent way
    * @param executor execution context of the calling SEC
    * @param system actor system context of the calling SEC
    * @return true if the operation was done and met the forward conditions, false otherwise
    */
  def executeForward()(implicit executor: ExecutionContext, system: ActorSystem): Future[Boolean] = {
    return doOperation(forwardId, pathForward, forwardCondition, true)
  }

  /**
    * Execute the revert path of this SagaOperation in an idem potent way
    * @param executor execution context of the calling SEC
    * @param system actor system context of the calling SEC
    * @return true if the operation was done and met the revert conditions or bestEffort reverse is true, false otherwise
    */
  def executeRevert()(implicit executor: ExecutionContext, system: ActorSystem): Future[Boolean] = {
    return doOperation(reverseId, pathRevert, _ => true, false)
  }

  private def doOperation(operationId: Long, path: URI, conditions: String => Boolean, forward: Boolean)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[Boolean] ={
    executionState = OperationState.PENDING
    val finalUri: String = buildPath(operationId, path)


    val responseFuture: Future[HttpResponse] = sendRequest(finalUri)
    responseFuture.map( response ⇒
      if(forward)
        forwardOperationDone(conditions(response.toString))
      else
        reverseOperationDone(conditions(response.toString))
    )
  }

  /**
    * Evaluate the condition of the forward operation and the current SagaOperation state
    */
  private def forwardOperationDone: Boolean => Boolean={
    case true   =>
      if( resultState == ResultState.NONE){
        resultState = ResultState.SUCCESS
      } else if(resultState == ResultState.TIMEOUT){
        throw new TimeoutException("SagaOperation: " + this.toString + " timed out without timeout strategy.")
      } else {throw new IllegalStateException("SagaExecutionState: " + executionState + " and SagaOperationState: " + resultState + " are an invalid combination for forward operations." )}
      executionState = OperationState.DONE
      true
    case false  =>
      if(resultState == ResultState.NONE) {
        resultState = ResultState.FAILURE
        executionState = OperationState.IDLE
      } else if(resultState == ResultState.TIMEOUT){
        executionState = OperationState.DONE
      } else{
        throw new Exception("Saga Operation was incorrectly executed")
      }
      false
  }

  /**
    * Evaluate the condition of the reverse operation and the current SagaOperation state
    */
  private def reverseOperationDone: Boolean => Boolean={
    case true   =>
      if(resultState == ResultState.SUCCESS){
        executionState = OperationState.DONE
        resultState == ResultState.NONE
      } else if(resultState == ResultState.TIMEOUT && bestEffortReverse){
        executionState = OperationState.DONE
      } else if(resultState == ResultState.TIMEOUT){
        throw new TimeoutException("SagaOperation: " + this.toString + " timed out without timeout strategy.")
      } else {
        throw new IllegalStateException(
          "SagaExecutionState: " + executionState +
            " and SagaOperationState: " + resultState +
            " are an invalid combination for reverse operations." )
      }
      true
    case false  =>
      if (resultState == ResultState.SUCCESS && bestEffortReverse) {
        executionState = OperationState.DONE
      } else if (resultState == ResultState.TIMEOUT && bestEffortReverse) {
        executionState = OperationState.DONE
      } else if(resultState == ResultState.TIMEOUT){
        throw new TimeoutException("SagaOperation: " + this.toString + " timed out without timeout strategy.")
      } else if (resultState == ResultState.SUCCESS) {
        throw new Exception("Failed to revert SagaOperation: " + this.toString)
      } else {
        throw new Exception("Saga Operation was incorrectly executed")
      }
      false
  }

  private def buildPath(operationId: Long, path: URI): String ={
    return path.toString + "/" + operationId.toString
  }

  private def sendRequest(path: String, numTries: Int = 0)
                         (implicit executor: ExecutionContext, system: ActorSystem): Future[HttpResponse] = {

    val connectionSettings = ClientConnectionSettings(system).withIdleTimeout(baseTimeout + timeoutScaling * numTries seconds)
    val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system).withConnectionSettings(connectionSettings)

    return Http()(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = path.toString), settings = connectionPoolSettings).recover{
      case exception: TimeoutException =>
        if(numTries >= SagaStorage.MAX_NUM_TRIES && !bestEffortReverse){
          resultState = ResultState.TIMEOUT
          return Future.failed(exception)
        }else {
          return sendRequest(path, numTries + 1)
        }
    }
  }
}
