package com.example

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import com.example.HotPotato.{Pass, SetNext}

object HotPotatoGame{
  val NUM_PLAYERS = 5
  val HOT_POTATO_LOSING_NUM = 11
}

object HotPotato {
  case class Pass(num: Int)
  case class SetNext(next:ActorRef)

}

class HotPotato(index: Int, next: ActorRef) extends Actor with ActorLogging {
  import HotPotato._
  import HotPotatoGame._
  var currVal = 0
  var nextReceiver = next
  def receive = {
    case Pass(num) => {
      log.info(s"Actor index $index got number $num")
      if(num != HOT_POTATO_LOSING_NUM) {
        currVal = num + 1
        nextReceiver ! Pass(currVal)
      } else {
        log.info("I lose :<")
      }
    }
    case SetNext(next) => {
      nextReceiver = next;
    }
  }


}


object AkkaHotPotato extends App {
  import HotPotatoGame._
  // Create the 'helloAkka' actor system
  val system: ActorSystem = ActorSystem("helloAkka")

  //#create-actors
  // Create the printer actor
  var refs = List[ActorRef]()
  refs =  system.actorOf(Props(classOf[HotPotato], NUM_PLAYERS-1, null), "player" + (NUM_PLAYERS-1)) :: refs
  for(i <- 0 to NUM_PLAYERS-2 ){
    refs =  system.actorOf(Props(classOf[HotPotato], NUM_PLAYERS - i - 2, refs.head), "player" + ( NUM_PLAYERS - i - 2)) :: refs
  }
  refs.last ! SetNext(refs.head)

  refs.head ! Pass(0)

  println(refs.size)
}
