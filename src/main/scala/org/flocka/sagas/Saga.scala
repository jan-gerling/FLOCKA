package org.flocka.sagas

/**
  * A saga is a directed acyclic graph of operations to be executed according to the SAGA protocol
  *
  * In this case we arent using true sagas, because not all DAGs can be represented. For our purposes,
  * this is more than enough however, and we avoid adding even more dependencies.
  *
  * Did not choose to perform builder pattern as these are hard to implement safely in scala
  */
class Saga extends Serializable {
  var dagOfOps: List[List[SagaOperation]] =  List(List())
  var mapOfOps: Map[Long, SagaOperation] = Map()

  def addConcurrentOperation(sagaOp: SagaOperation) = {
    dagOfOps.last :+ sagaOp
    mapOfOps = mapOfOps + (sagaOp.id -> sagaOp)
  }

  def addSequentialOperation(sagaOp: SagaOperation) = {
    var newList: List[SagaOperation] = List(sagaOp)
    mapOfOps = mapOfOps + (sagaOp.id -> sagaOp)

   dagOfOps :+ newList
  }
}

