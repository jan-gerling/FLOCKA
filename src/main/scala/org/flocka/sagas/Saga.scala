package org.flocka.sagas
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
/**
  * A saga is a directed acyclic graph of operations to be executed according to the SAGA protocol
  *
  * In this case we arent using true sagas, because not all DAGs can be represented. For our purposes,
  * this is more than enough however, and we avoid adding even more dependencies.
  *
  * Did not choose to perform builder pattern as these are hard to implement safely in scala
  */
class Saga extends Serializable {
  var dagOfOps: mutable.ListBuffer[mutable.ListBuffer[SagaOperation]] =  mutable.ListBuffer(mutable.ListBuffer())
  var mapOfOps: mutable.Map[Long, SagaOperation] = mutable.Map()

  def addConcurrentOperation(sagaOp: SagaOperation) = {
    dagOfOps.last += sagaOp
    mapOfOps += (sagaOp.id -> sagaOp)
  }

  def addSequentialOperation(sagaOp: SagaOperation) = {
    val newList: mutable.ListBuffer[SagaOperation] = mutable.ListBuffer(sagaOp)
    mapOfOps += (sagaOp.id -> sagaOp)

   dagOfOps += newList
  }
}

