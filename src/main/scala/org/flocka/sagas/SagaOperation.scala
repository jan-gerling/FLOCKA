package org.flocka.sagas

import java.util.UUID.randomUUID

import akka.http.scaladsl.model.HttpResponse

case class SagaOperation(pathForward: String, pathInvert: String, forwardSuccessfulCondition: HttpResponse => Boolean) {
  var state: OperationState.Value = OperationState.UNPERFORMED
}