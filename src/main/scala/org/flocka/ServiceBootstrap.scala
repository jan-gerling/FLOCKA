package org.flocka

import org.flocka.ServiceBasics.ServiceStart
import org.flocka.Services.Order.{OrderService, OrderSharding}
import org.flocka.Services.Stock.{StockService, StockSharding}
import org.flocka.Services.User.{UserService, UserSharding}

/**
  * Run this object from the command line to start a service.
  * UserService or u to start the UserService.
  * StockService or s to start the StockService.
  * OrderService or o to start the OrderService.
  */
object ServiceBootstrap extends App {
  override def main(args: Array[String]): Unit = {
    if (args.length == 0) {
      println("ServiceBootstrap requires one argument defining the service to start, e.g. UserService")
    } else if (args.length > 1){
      println("ServiceBootstrap only handles one argument.")
    } else if(args(0).equalsIgnoreCase("UserService") || args(0).equalsIgnoreCase("u")){
      ServiceStart.startService(UserSharding, UserService)
    } else if(args(0).equalsIgnoreCase("StockService") || args(0).equalsIgnoreCase("s")){
      ServiceStart.startService(StockSharding, StockService)
    } else if(args(0).equalsIgnoreCase("OrderService") || args(0).equalsIgnoreCase("o")){
      ServiceStart.startService(OrderSharding, OrderService)
    }
  }
}