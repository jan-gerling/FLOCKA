package org.flocka.Services.Order

import org.flocka.ServiceBasics.{IdResolver, MessageTypes}

/**
  * Define all allowed order service communications here. They have to comply to the CQRS paradigm.
  */
object OrderServiceComs{

  /**
    /orders/create/{user_id}
    POST - creates an order for the given user, and returns an order_id
    */
  final case class CreateOrder(orderId: Long, userId: Long) extends MessageTypes.Command{
    override val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
  }
  final case class OrderCreated(orderId: Long, userId: Long) extends MessageTypes.Event

  /**
    /orders/remove/{order_id}
    DELETE - deletes an order by ID
    return success/failure
    */
  final case class DeleteOrder(orderId: Long) extends MessageTypes.Command{
    override val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
  }
  final case class OrderDeleted(orderId: Long, success: Boolean) extends MessageTypes.Event

  /**
    /orders/find/{order_id}
    GET - retrieves the information of an order (payment status, items included and user id)
    */
  final case class FindOrder(orderId: Long) extends MessageTypes.Query{
    override val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
  }
  final case class OrderFound(orderId: Long,userId: Long, paymentStatus: Boolean, items: List[(Long, Long)]) extends MessageTypes.Event

  /**
    /orders/addItem/{order_id}/{item_id}
    POST - adds a given item in the order given
    Returns success or failure, depending on the payment status.
  */
  final case class AddItem(orderId: Long, itemId: Long, operation: Long) extends MessageTypes.Command {
    override val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
    override val operationId: Long = operation
  }
  final case class ItemAdded(orderId: Long, itemId: Long, price: Long, success: Boolean, operation: Long) extends MessageTypes.Event{
    override val operationId: Long = operation
  }

  /**
    /orders/removeItem/{order_id}/{item_id}
    DELETE - removes the given item from the given order
    Returns success or failure, depending on the payment status.
  */
  final case class RemoveItem(orderId: Long, itemId: Long, operation: Long) extends MessageTypes.Command {
    override val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
    override val operationId: Long = operation
  }
  final case class ItemRemoved(orderId: Long, itemId: Long, success: Boolean, operation: Long) extends MessageTypes.Event{
    override val operationId: Long = operation
  }

  /**
    /orders/checkout/{order_id}
    POST - makes the payment (via calling the payment service), subtracts the stock (via the stock service)
    return a status success or failure
  */
  final case class CkeckoutOrder(orderId: Long) extends MessageTypes.Command{
    override val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
  }
  final case class OrderCheckedOut(orderId: Long, success: Boolean) extends MessageTypes.Event
}