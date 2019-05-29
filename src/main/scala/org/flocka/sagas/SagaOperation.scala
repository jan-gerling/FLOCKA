package org.flocka.sagas

import java.util.UUID.randomUUID

import akka.http.scaladsl.model.HttpResponse
import org.flocka.ServiceBasics.MessageTypes.Event

case class SagaOperation(pathForward: String, pathInvert: String, forwardSuccessfulCondition: Function1[String, Boolean]) {
  var state: OperationState.Value = OperationState.UNPERFORMED
}