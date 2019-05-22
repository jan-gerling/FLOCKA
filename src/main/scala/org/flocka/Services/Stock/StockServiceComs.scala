package org.flocka.Services.Stock

import org.flocka.ServiceBasics.MessageTypes
import org.flocka.Services.User.IdManager

object StockServiceComs {

  /**
    * /stock/item/create
    * POST - returns and ID
    */
  final case class CreateItem(itemId: Long) extends MessageTypes.Command {
    override val entityId: Long = IdManager.extractRepositoryId(itemId)
    override val key: Long = itemId
  }
  final case class ItemCreated(itemId: Long) extends MessageTypes.Event

  /**
    * /stock/availability/{item_id}
    * GET - returns the current availability of a stock item
    */
  final case class GetAvailability(itemId: Long) extends MessageTypes.Query {
    override val entityId: Long = IdManager.extractRepositoryId(itemId)
    override val key: Long = itemId
  }
  final case class AvailabilityGot(itemId: Long, amount:Long) extends MessageTypes.Event

  /**
    * /stock/add/{item_id}/{amount}
    * POST - add the amount to the available amount of the stock item
    */
  final case class IncreaseAvailability(itemId: Long, amount: Long) extends MessageTypes.Command {
    override val entityId: Long = IdManager.extractRepositoryId(itemId)
    override val key: Long = itemId
  }
  final case class AvailabilityIncreased(itemId:Long, amount:Long, success: Boolean) extends MessageTypes.Event

  /**
    * /stock/substract/{item_id}/{amount}
    * POST -  substracts the amount from the available amount of the stock item
    */
  final case class DecreaseAvailability(itemId: Long, amount:Long) extends MessageTypes.Command {
    override val entityId: Long = IdManager.extractRepositoryId(itemId)
    override val key: Long = itemId
  }
  final case class AvailabilityDecreased(itemId: Long, amount:Long, success: Boolean) extends MessageTypes.Event
}
