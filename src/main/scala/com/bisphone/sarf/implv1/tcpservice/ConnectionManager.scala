package com.bisphone.sarf.implv1.tcpservice

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Terminated}
import akka.stream.scaladsl.Tcp
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class ConnectionManager(name: String) extends Actor {

   val loggerName = s"SARFServer(${name}).ConnectionManager"
   val logger = LoggerFactory getLogger loggerName

   private val dict = mutable.HashMap.empty[ActorRef, Tcp.IncomingConnection]

   private def addr2str (addr: InetSocketAddress): String = s"${addr.getHostName}:${addr.getPort}"

   def receive: Receive = {

      case Director.Event.NewConnection(ref, actor) =>

         dict(actor) = ref
         context watch actor

         if (logger.isInfoEnabled()) logger info
            s"""{
                |"subject":      "${loggerName}.New",
                |"host":         "${addr2str(ref.localAddress)}",
                |"remote":       "${addr2str(ref.remoteAddress)}",
                |"actor":        "${self}",
                |"conn":         "${actor}",
                |"totalConnections":   ${dict.size}
                |}""".stripMargin

      case Terminated(actor) if dict contains actor =>

         val Some(ref) = dict remove actor

         if (logger.isInfoEnabled()) logger info
            s"""{
                |"subject":      "${loggerName}.Closed",
                |"host":         "${addr2str(ref.localAddress)}",
                |"remote":       "${addr2str(ref.remoteAddress)}",
                |"actor":        "${self}",
                |"conn":         "${actor}"
                |"totalConnections":   ${dict.size}
                |}""".stripMargin
   }

   override def preStart (): Unit = {
      if (logger.isDebugEnabled()) logger debug s"""{"subject": "${loggerName}.preStart"""
   }

   override def postStop (): Unit = {
      if (logger.isDebugEnabled()) logger debug s"""{"subject": "${loggerName}.postStop"""
      dict.keys.foreach {
         _ ! PoisonPill
      }
   }

}

private[implv1] object ConnectionManager {
   def props (name: String) = Props {
      new ConnectionManager(name)
   }
}