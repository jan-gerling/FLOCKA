package org.flocka.sagas

object OperationState extends Enumeration {
  val UNPERFORMED, SUCCESS_NO_ROLLBACK, SUCCESS_ROLLEDBACK, FAILURE = Value
}
