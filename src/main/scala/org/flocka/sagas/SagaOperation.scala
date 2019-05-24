package org.flocka.sagas

import java.util.UUID.randomUUID

import akka.http.scaladsl.model.HttpResponse

case class SagaOperation(pathForward: String, pathInvert: String, forwardSuccessfulCondition: HttpResponse => Boolean){
  val id = randomUUID().toString.hashCode.toLong
  val backwardId = randomUUID().toString.hashCode.toLong
  var success = false
  var completed = false
  var invertCompleted = false
}