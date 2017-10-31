package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.actor.{ActorSubscriber, MaxInFlightRequestStrategy, RequestStrategy}
import akka.util.ByteString
import org.slf4j.LoggerFactory

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class Reader (name: String, director: ActorRef, demands: Int) extends ActorSubscriber {


    val loggerName = s"$name.sarf.client.reader"
    val logger = LoggerFactory getLogger loggerName

    import akka.stream.actor.ActorSubscriberMessage._

    override def preStart (): Unit = {
        context watch director
        request(demands)
        if (logger.isInfoEnabled()) logger info s"PreStart, Self: ${self}, Director: ${director}, Ignore Arg: Demands(${demands})"
    }

    override def postStop (): Unit = {
        try context unwatch director finally ();
        if (logger.isInfoEnabled()) logger info s"PostStop, Self: ${self}, Director: ${director}, Ignore Arg: Demands(${demands})"
    }

    override val requestStrategy = new RequestStrategy {

        override def toString: String = s"RequestStrategy(FixedDemamds: 1)"

        override def requestDemand(requestDemand: Int): Int = 1
    }

    override def receive: Receive = {

        case OnNext(bytes: ByteString) =>
            if (logger.isTraceEnabled()) logger trace s"OnNext, Bytes: ${bytes.size}"
            director ! Director.Event.Received(bytes)

        case OnComplete =>
            if (logger.isInfoEnabled()) logger info s"OnComplete, Self: ${self}"
            context stop self

        case OnError(cause) =>
            if (logger.isErrorEnabled) logger error (s"OnError, Self: ${self}", cause)
            context stop self

        case Terminated(ref /*director*/) =>
            if (logger.isWarnEnabled()) logger warn s"Terminated, Origin: ${ref}, Self: ${self}"
            context stop self
    }

}

object Reader {
    def props (name: String, director: ActorRef, demands: Int = 100) = Props {
        new Reader(name, director, demands)
    }
}
