package org.flocka.Services.Payment

import org.flocka.ServiceBasics.{IdResolver, MessageTypes}

/**
  * Define all allowed payment service communications here. They have to comply to the CQRS scheme.
  */
object PaymentServiceComs{
  /**
  /payment/pay/{user_id}/{order_id}
    POST - returns failure if credit is not enough
    */
  final case class PayPayment(userId: Long, orderId: Long, operation: Long) extends MessageTypes.Command{
    val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
    override val operationId: Long = operation
  }
  final case class PaymentPayed(userId: Long, orderId: Long, status: Boolean, operation: Long) extends MessageTypes.Event{
    override val operationId: Long = operation
  }

  /**
  /payment/cancelPayment/{user_id}/{order_id}
    POST - cancels payment made by a specific user for a specific order
    */
  final case class CancelPayment(userId: Long, orderId: Long, operation: Long) extends MessageTypes.Command {
    val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
    override val operationId: Long = operation
  }
  final case class PaymentCanceled(userId: Long, orderId: Long, status: Boolean, operation: Long) extends MessageTypes.Event{
    override val operationId: Long = operation
  }

  /**
  /payment/status/{order_id}
    GET - returns the status of the payment
    */
  final case class GetPaymentStatus(orderId: Long) extends MessageTypes.Query {
    val entityId: Long = IdResolver.extractRepositoryId(orderId)
    override val key: Long = orderId
  }
  final case class PaymentStatusFound(orderId: Long, paymentDetails: Boolean) extends MessageTypes.Event
}