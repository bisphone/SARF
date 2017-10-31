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

   val loggerName = s"${name}.sarf.server.connection-manager"
   val logger = LoggerFactory getLogger loggerName

   private val dict = mutable.HashMap.empty[ActorRef, Tcp.IncomingConnection]

   private def addr2str (addr: InetSocketAddress): String = s"${addr.getHostName}:${addr.getPort}"

   def receive: Receive = {

      case Director.Event.NewConnection(ref, actor) =>

         dict(actor) = ref
         context watch actor

         if (logger.isInfoEnabled()) logger info s"NewConnection, Total: ${dict.size}, Remote: ${addr2str(ref.remoteAddress)}"
         if (logger.isDebugEnabled()) logger debug s"NewConnection, Local: ${addr2str(ref.localAddress)}, Remote: ${addr2str(ref.remoteAddress)}, Self: ${self}"

      case Terminated(actor) if dict contains actor =>

         val Some(ref) = dict remove actor
         if (logger.isInfoEnabled) logger info s"ClosedConnection, Total: ${dict.size}, Remote: ${addr2str(ref.remoteAddress)}"
         if (logger.isDebugEnabled()) logger debug s"ClosedConnection, Local: ${addr2str(ref.localAddress)}, Remote: ${addr2str(ref.remoteAddress)}, Self: ${self}"
   }

   override def preStart (): Unit = {
      if (logger.isInfoEnabled()) logger info s"PreStart"
   }

   override def postStop (): Unit = {
      if (logger.isDebugEnabled()) logger info s"PostStop"
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