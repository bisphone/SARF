package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.util.ByteString

import scala.collection.mutable

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class Writer (director: ActorRef) extends ActorPublisher[ByteString] {

   import ActorPublisherMessage._

   override def preStart (): Unit = {
      context watch director
   }

   val queue = mutable.Queue.empty[ByteString]

   def deliver (): Unit =
      while (totalDemand > 0 && queue.nonEmpty)
         onNext(queue.dequeue())

   def send (request: ByteString) = {
      queue enqueue request
      deliver()
   }

   def receive: Receive = {
      case request: ByteString => send(request)
      case Request(n) => deliver()
      case Cancel => context stop self
      case Terminated(ref /* director */) => context stop self
   }

}

object Writer {
   def props (director: ActorRef) = Props {
      new Writer(director)
   }
}
