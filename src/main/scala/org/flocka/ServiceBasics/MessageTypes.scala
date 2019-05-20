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
    val entityId: Long
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
