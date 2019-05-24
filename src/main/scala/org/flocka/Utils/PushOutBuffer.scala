package org.flocka.Utils

import scala.collection.mutable
import scala.collection.mutable.Queue

/**
  * Interface for a simple circular buffer, allowing to check for elements
  * @tparam T
  */
trait PushOutBuffer[T] {
        /**
          * max number of elements in this circular collection
          */
        def capacity: Int

        /**
          * number of elements currently residing in the collection
          */
        def size: Int

        /**
          * Push a new element into the collection, and if necessary remove an old one
          */
        def push(element: T): Option[T]
}

case class PushOutQueueBuffer[T](capacity: Int) extends PushOutBuffer[T]{
        val queueBuffer: Queue[T] = Queue.empty

        /**
          * number of elements currently inside the collection
          */
        override def size: Int = queueBuffer.size

        /**
          * Push a new element into the collection, and if necessary remove an old one
          */
        def push(element: T): Option[T] = {
                if (capacity >= size) {
                        queueBuffer.enqueue(element)
                        return None
                } else {
                        queueBuffer.enqueue(element)
                        return Some(queueBuffer.dequeue)
                }
        }
}

/**
  * Implementation of a circular buffer using scala mutable queue to store order addition and mutable Hash map to store values and fast look ups internally.
  */
case class PushOutHashmapQueueBuffer[K, T](capacity: Int) {
        val map: mutable.Map[K, T] = mutable.Map.empty
        val queueBuffer: PushOutQueueBuffer[K] = PushOutQueueBuffer(capacity)

        def size: Int = map.size

        def contains(key: K): Boolean = map.contains(key)

        def push(key: K, element: T): PushOutHashmapQueueBuffer[K, T] = {
                queueBuffer.push(key) match{
                        case Some(removeKey) => map -= removeKey
                        case None =>
                }
                map += key -> element
                return this
        }

        def pop(key: K): T = {
                return popOption(key).getOrElse(throw new NoSuchElementException)
        }

        def popOption(key: K): Option[T] = {
                return map.get(key)
        }

}