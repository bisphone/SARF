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

    val loggerName = s"${name}.sarf.client.writer"
    val logger = LoggerFactory getLogger loggerName

    import ActorPublisherMessage._

    override def preStart (): Unit = {
        context watch director
        if (logger.isInfoEnabled) logger info s"PreStart, Self: ${self}, Director: ${director}"
    }

    override def postStop (): Unit = {
        try context unwatch director finally ();
        if (logger isInfoEnabled ()) logger info s"PostStop, Self: ${self}, Director: ${director}"
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
        case request: ByteString =>
            if (logger.isTraceEnabled()) logger trace s"Send, Bytes:${request.size}"
            send(request)

        case Request(n) => deliver()
            if (logger.isTraceEnabled()) logger trace s"Request, Demands: ${n}"

        case Cancel =>
            if (logger.isInfoEnabled) logger info s"Cancel, Self: ${self}"
            context stop self

        case Terminated(ref /* director */) =>
            if (logger.isWarnEnabled) logger warn s"Terminated, Origin: ${ref}, Self: ${self}"
            context stop self
    }

}

object Writer {
    def props (name: String, director: ActorRef) = Props {
        new Writer(name, director)
    }
}
