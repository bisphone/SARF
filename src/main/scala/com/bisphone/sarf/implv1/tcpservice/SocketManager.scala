package com.bisphone.sarf.implv1.tcpservice

import akka.actor.{Actor, ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Tcp}
import akka.util.ByteString
import com.bisphone.sarf.implv1.util.TCPConfigForServer
import org.slf4j.{Logger, LoggerFactory}
import com.bisphone.util._
import com.bisphone.std._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.Await


/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
private[implv1] class SocketManager (
    name: String,
    config: TCPConfigForServer,
    connectionManager: ActorRef,
    requestFlow: Flow[ByteString, ByteString, ActorRef]
) extends Actor {

   val loggerName = s"SARFServer(${name}).SockerManger"
   val logger = LoggerFactory getLogger loggerName

   implicit val system = context.system
   implicit val materializer = ActorMaterializer()
   implicit val ec = context.system.dispatcher

   var bindingSt: Tcp.ServerBinding = _

   def binding: Receive = {
      case Right(st: Tcp.ServerBinding) =>

         bindingSt = st
         context become bound(st)
         context.parent ! Director.State.Bound(st)

         if (logger.isInfoEnabled()) logger info
            s"""{
                |"subject":         "${loggerName}.Binding => Bound",
                |"host":            "${config.host}:${config.port}",
                |"backlog":         ${config.backlog}
                |}""".stripMargin

      case Left(cause: Throwable) =>

         if (logger.isErrorEnabled()) logger error(
            s"""{
                |"subject":      "${loggerName}.BindingFailure => throw SocketBindingFailure",
                |"host":         "${config.host}:${config.port}",
                |"backlog":      ${config.backlog},
                |"error":        "${cause.getMessage}"
                |}""".stripMargin, cause)

         throw new Director.Exception.SocketBindFailure("Failure in binding", cause)

      case Director.Command.UnbindByDemand(requestor) =>
         unbindRequestors += requestor
         context become unbindOnBound

         if (logger.isDebugEnabled()) logger debug
            s"""{
              |"subject":     "${loggerName}.Binding => UnbindByDemand",
              |"desc":        "Try to unbind not-bound socket"
              |}""".stripMargin
   }

   def unbindOnBound: Receive = {
      case Right(st: Tcp.ServerBinding) =>

         bindingSt = st

         if (logger.isDebugEnabled()) logger debug
            s"""{
              |"subject":     "${loggerName}."
              |'subject': 'Socket.*',
              |'desc': 'Try to unbind newly bound socket'
              |}""".stripMargin

         unbound(st)

      case Left(cause: Throwable) =>

         if (logger.isWarnEnabled()) logger warn(
            s"""{
                |"subject":     "${loggerName}.UnbindFailure => Stop",
                |"host":        "${config.host}:${config.port}",
                |"backlog":     ${config.backlog},
                |"errorType":   "${cause.getClass.getName}",
                |"errorMsg":    "${cause.getMessage}",
                |"desc":         "UnbindOnBound"
                |}""".stripMargin, cause)

           context stop self
   }

   def bound (st: Tcp.ServerBinding): Receive = {
      case Director.Command.UnbindByDemand(requestor) =>

         if (logger.isDebugEnabled()) logger debug s"""{"subject":  "${loggerName}.Bound => Unbind"}"""

         unbindRequestors += requestor
         unbound(st)
   }

   def unbinding: Receive = {
      case Director.State.Unbound =>

         if (logger.isInfoEnabled()) logger info
            s"""{
                |"subject":         "${loggerName}.Unbound => Stop",
                |"host":            "${config.host}:${config.port}"
                |}""".stripMargin

         context.parent ! Director.Event.UnboundByDemand(unbindRequestors.toList)

         context stop self
   }

   def receive: Receive = binding

   // =========================================================

   val unbindRequestors = mutable.ListBuffer.empty[ActorRef]

   private def unbound (st: Tcp.ServerBinding): Unit = {
      context become unbinding
      st.unbind.foreach { _ => self ! Director.State.Unbound }
   }

   private val tcpFlow =
      Tcp().bind(config.host, config.port, backlog = config.backlog) map { conn =>
         try {
            val connectionDirector = (conn handleWith requestFlow)
            connectionManager ! Director.Event.NewConnection(conn, connectionDirector)
         } catch {
            case cause: Throwable => logger error("Error on materializing income-connection", cause)
         }
      } to Sink.ignore

   override def preStart (): Unit = {
      tcpFlow.run.map {
         Right(_)
      }.recover {
         case cause => Left(cause)
      }.foreach(st => self ! st)
      if (logger.isDebugEnabled()) logger debug s"""{"subject": "${loggerName}.preStart"}"""
   } // Async !

   override def postStop (): Unit = synchronized {

      Await.ready(bindingSt.unbind(), 3 seconds)
      if (logger.isDebugEnabled()) logger debug s"""{"subject": "${loggerName}.postStop"}"""
   }

}

private[implv1] object SocketManager {
   def props (
       name: String,
       config: TCPConfigForServer,
       connectionManager: ActorRef,
       requestFlow: Flow[ByteString, ByteString, ActorRef]
   ) = Props {
      new SocketManager(name, config, connectionManager, requestFlow)
   }
}
