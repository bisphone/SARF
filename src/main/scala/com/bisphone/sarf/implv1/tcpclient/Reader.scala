package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.actor.{ActorSubscriber, MaxInFlightRequestStrategy, RequestStrategy}
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
        if (logger isDebugEnabled()) logger debug
            s"""{
               |"subject":     "${loggerName}.preStart",
               |"actor":       "${self}",
               |"director":    "${director}",
               |"deamnds":     ${demands}
               |}""".stripMargin
    }

    override def postStop (): Unit = {

        if (logger isDebugEnabled()) logger debug
            s"""{
               |"subject":      "${loggerName}.postStop",
               |"actor":        "${self}",
               |"director":     "${director}"
               |}""".stripMargin
    }

    override val requestStrategy = new RequestStrategy {

        override def toString: String = s"RequestStrategy(fixedDemamds"

        override def requestDemand(requestDemand: Int): Int = 1
    }

    override def receive: Receive = {
        case OnNext(bytes: ByteString) =>
            director ! Director.Event.Received(bytes)

        case OnComplete =>

            if (logger isWarnEnabled()) logger warn
                s"""{
                   |"subject":   "${loggerName}.StreamHasCompleted => Stop",
                   |"actor":     "${self}"
                   |}""".stripMargin

            context stop self

        case OnError(cause) =>

            if (logger isErrorEnabled()) logger error(
                s"""{
                   |"subject":   "${loggerName}.StreamHasFailed => Stop",
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
                   |"subject":   "${loggerName}.DirectorHasTerminated => Stop",
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
