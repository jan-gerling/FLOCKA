package org.flocka
/*
All custom created messages types in FLOCKA have to extend one of the following traits to allow our system to implement CQRS.
More details on CQRS: https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10)
 */
object MessageTypes {
  /*
  Every command with a potential change of data has to extend this trait.
   */
  trait Command {
    def objectId: Long
  }

  /*
  This trait is added to all persisted operations, thus whenever an event is received its contents was already done.
   */
  trait Event

  /*
  Only requests with no change to data are allowed to extend this trait.
   */
  trait Query {
    def objectId: Long
  }
}