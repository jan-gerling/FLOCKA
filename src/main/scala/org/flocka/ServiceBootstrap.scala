package org.flocka

import org.flocka.ServiceBasics.ServiceStart
import org.flocka.Services.Order.{OrderService, OrderSharding}
import org.flocka.Services.Payment.{PaymentService, PaymentSharding}
import org.flocka.Services.Stock.{StockService, StockSharding}
import org.flocka.Services.User.{UserService, UserSharding}

/**
  * Run this object from the command line to start a service.
  */
object ServiceBootstrap extends App {
  override def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("[Error] Incorrect number of arguments.")
      printManual()
    } else if(args(0).equalsIgnoreCase("UserService") || args(0).equalsIgnoreCase("u")){
      ServiceStart.startService(UserSharding, UserService)
    } else if(args(0).equalsIgnoreCase("StockService") || args(0).equalsIgnoreCase("s")){
      ServiceStart.startService(StockSharding, StockService)
    } else if(args(0).equalsIgnoreCase("OrderService") || args(0).equalsIgnoreCase("o")) {
      ServiceStart.startService(OrderSharding, OrderService)
    } else if(args(0).equalsIgnoreCase("PaymentService") || args(0).equalsIgnoreCase("p")) {
      ServiceStart.startService(PaymentSharding, PaymentService)
    } else{
      println("[Error] Unknown argument: " + args(0))
      printManual()
    }
  }

  def printManual(): Unit ={
    println("ServiceBootstrap requires one argument defining the service to start.")
    println(" u / UserService       -   to start the UserService")
    println(" s / StockService      -   to start the StockService")
    println(" o / OrderService      -   to start the OrderService")
    println(" p / PaymentService    -   to start the PaymentService")
  }
}