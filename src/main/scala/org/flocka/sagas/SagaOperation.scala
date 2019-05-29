package org.flocka.sagas

case class SagaOperation(pathForward: String, pathInvert: String, forwardSuccessfulCondition: Function1[String, Boolean]) {
  var state: OperationState.Value = OperationState.UNPERFORMED
}