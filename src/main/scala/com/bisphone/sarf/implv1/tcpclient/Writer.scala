package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.util.ByteString
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class Writer (name: String, director: ActorRef) extends ActorPublisher[ByteString] {

   val loggerName = s"SARFClient(${name}).Writer"
   val logger = LoggerFactory getLogger loggerName

   import ActorPublisherMessage._

   override def preStart (): Unit = {

      context watch director

      if (logger isDebugEnabled ()) logger debug
          s"""{
             |"subject":      "preStart",
             |"actor":        "${self}",
             |"director":     "${director}"
             |}""".stripMargin
   }

   override def postStop(): Unit = {

      if (logger isDebugEnabled ()) logger debug
          s"""{
             |"subject":      "postStop",
             |"actor":        "${self}",
             |"director":     "${director}"
             |}""".stripMargin

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
      case Cancel =>

         if (logger isWarnEnabled ()) logger warn
             s"""{
                |"subject":      "Stream Has Canceled => Stop",
                |"actor":        "${self}",
                |"director":     "${director}"
                |}""".stripMargin

         context stop self

      case Terminated(ref /* director */) =>

         if (logger isWarnEnabled ()) logger warn
             s"""{
                |"subject":      "Director Has Terminated => Stop",
                |"actor":        "${self}",
                |"director":     "${director}"
                |}""".stripMargin

         context stop self
   }

}

object Writer {
   def props (name: String, director: ActorRef) = Props {
      new Writer(name, director)
   }
}
