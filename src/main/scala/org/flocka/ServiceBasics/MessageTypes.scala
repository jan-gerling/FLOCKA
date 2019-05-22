package org.flocka.ServiceBasics

/**
All custom created messages types in FLOCKA have to extend one of the following traits to allow our system to implement CQRS.
More details on CQRS: https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10)
 */
object MessageTypes {
  /**
  Supertype for Command and Queries.
    */
  trait Request  {


    /** entityId identifies the sharded entity whether it is a "User" or a "UserRepository" within the sharding region.
      * If one is sharding for example, the Users and not UserRepositories, entityId = least significant 52 bits of key
      * In our case, entity id is the middle 40 bits of key, as we sharded the UserRepositories.
      *
      * Example (Users):
      *     key:      391984595584172609
      *     entityId:   41852539780         ((key & 0x000FFFFFFFFFF000 )>> 12)
      *     shard:    87                  ((391984595584172609 & 0xFFF0000000000000) >> 52)
      */
    val entityId: Long

    /**
      * key identifies the final target of a Command. It identifies a single user, whether sharded or in a UserRepository
      * For order, it will be the orderId.
      * For payment, it will be the paymentId.
      * For stock, it will be the stockItemId.
      */
    val key: Long
  }

  /**
  Every command with a potential change of data has to extend this trait.
   */
  trait Command extends Request

  /**
  Only requests with no change to data are allowed to extend this trait.
   */
  trait Query extends Request

  /**
  This trait is added to all persisted operations, thus whenever an event is received its contents was already done.
    */
  trait Event

}
