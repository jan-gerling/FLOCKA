package org.flocka.Services.Payment

import akka.actor.{Actor, Props}

// ToDo: implement validator properly
/**
This Object stores the props to create a PaymentActorSupervisor.
  */
object Validator{
  /**
  Props used to create a new PaymentActorSupervisor.
  "Props is a configuration class to specify options for the creation of actors."
  For more details on props look here: https://doc.akka.io/docs/akka/2.5.5/scala/actors.html
    */
  def props(): Props = Props(new Validator())
}

/**
  * Supervisor or guardian for a range of payment actors, implementing the ActorSupervisorBase class.
  * Responsibilities: create ids for new Payment, map orderid on paymentactor, send command/ query to the correct payment actor and pipe the result back to the service, validate payment actor response for a command/ query
  */
class Validator extends Actor {
  /**
  Receive the commands here and process them, to keep the communication running.
    */
  override def receive: Receive = {
    case command @ _  => throw new IllegalArgumentException(command.toString)


  }
}