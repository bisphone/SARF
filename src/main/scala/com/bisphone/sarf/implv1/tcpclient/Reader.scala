package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.actor.{ActorSubscriber, MaxInFlightRequestStrategy}
import akka.util.ByteString
import org.slf4j.LoggerFactory

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class Reader (name: String, director: ActorRef, demands: Int) extends ActorSubscriber {


   val loggerName = s"SARFClient(${name}).Reader"
   val logger = LoggerFactory getLogger loggerName

   import akka.stream.actor.ActorSubscriberMessage._

   override def preStart (): Unit = {
      context watch director
      request(demands)
      if (logger isDebugEnabled ()) logger debug
          s"""{
             |"subject":     "preStart",
             |"actor":       "${self}",
             |"director":    "${director}",
             |"deamnds":     ${demands}
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

   override val requestStrategy = new MaxInFlightRequestStrategy(max = demands) {
      override def inFlightInternally: Int = demands
   }

   override def receive: Receive = {
      case OnNext(bytes: ByteString) =>
         director ! Director.Event.Received(bytes)
      case OnComplete =>

         if (logger isWarnEnabled()) logger warn
             s"""{
                |"subject":   "Stream Has Completed => Stop",
                |"actor":     "${self}"
                |}""".stripMargin

         context stop self

      case OnError(cause) =>

         if (logger isErrorEnabled()) logger error (
             s"""{
                |"subject":   "Stream Has Failed => Stop",
                |"actor":     "${self}",
                |"errorType": "${cause.getClass.getName}",
                |"errorMsg":  "${cause.getMessage}"
                |}""".stripMargin,
             cause
         )

         context stop self

      case Terminated(ref /*director*/) =>

         if (logger isWarnEnabled()) logger warn
             s"""{
                |"subject":   "Director Has Terminated => Stop",
                |"actor":     "${self}",
                |"director":  "${director}"
                |}""".stripMargin

         context stop self
   }

}

object Reader {
   def props (name: String, director: ActorRef, demands: Int = 100) = Props {
      new Reader(name, director, demands)
   }
}
