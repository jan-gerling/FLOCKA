package org.flocka.sagas

import java.util.concurrent.CopyOnWriteArrayList

/**
  * A saga is a directed acyclic graph of operations to be executed according to the SAGA protocol
  *
  * In this case we arent using true sagas, because not all DAGs can be represented. For our purposes,
  * this is more than enough however, and we avoid adding even more dependencies.
  *
  * Did not choose to perform builder pattern as these are hard to implement safely in scala
  */
case class Saga() {
  lazy val rng = new scala.util.Random(System.currentTimeMillis())
  lazy val forwardId = rng.nextLong()
  lazy val backwardId = rng.nextLong()

  val dagOfOps: CopyOnWriteArrayList[CopyOnWriteArrayList[SagaOperation]] = new CopyOnWriteArrayList()
  dagOfOps.add(new CopyOnWriteArrayList())

  def addConcurrentOperation(sagaOp: SagaOperation) = {
    dagOfOps.get(dagOfOps.size() - 1).add(sagaOp)
  }

  def addSequentialOperation(sagaOp: SagaOperation) = {
    val newList: CopyOnWriteArrayList[SagaOperation] = new CopyOnWriteArrayList()
    newList.add(sagaOp)

    dagOfOps.add(newList)
  }
}

