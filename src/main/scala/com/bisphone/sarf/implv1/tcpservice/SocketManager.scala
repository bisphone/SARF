package com.bisphone.sarf.implv1.tcpservice

import akka.actor.{Actor, ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Tcp}
import akka.util.ByteString
import com.bisphone.sarf.implv1.util.TCPConfigForServer
import org.slf4j.Logger
import com.bisphone.util._
import com.bisphone.std._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.Await


/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
private[implv1] class SocketManager (
   config: TCPConfigForServer,
   connectionManager: ActorRef,
   requestFlow: Flow[ByteString, ByteString, ActorRef],
   logger: Logger
) extends Actor {

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
                |'subject': 'Socket.Bound',
                |'host': '${config.host}:${config.port}',
                |'backlog': ${config.backlog}
                |}""".stripMargin

      case Left(cause: Throwable) =>

         if (logger.isErrorEnabled()) logger error(
            s"""{
                |'subject': 'Socket.BoundFailure',
                |'host': '${config.host}:${config.port}',
                |'backlog': ${config.backlog},
                |'error': '${cause.getMessage}'
                |}""".stripMargin, cause)

         throw new Director.Exception.SocketBindFailure("Failure in binding", cause)

      case Director.Command.UnbindByDemand(requestor) =>
         unbindRequestors += requestor
         context become unbindOnBound

         if (logger.isDebugEnabled()) logger debug
            """{
              |'subject': 'Socket.*',
              |'desc': 'Try to unbind not-bound socket'
              |}""".stripMargin
   }

   def unbindOnBound: Receive = {
      case Right(st: Tcp.ServerBinding) =>

         bindingSt = st

         if (logger.isDebugEnabled()) logger debug
            """{
              |'subject': 'Socket.*',
              |'desc': 'Try to unbind newly bound socket'
              |}""".stripMargin

         unbound(st)

      case Left(cause: Throwable) =>
         if (logger.isWarnEnabled()) logger warn(
            s"""{
                |'subject': 'Socket.BoundFailure',
                |'host': '${config.host}:${config.port}',
                |'backlog': ${config.backlog},
                |'error': '${cause.getMessage}',
                |'desc': 'UnbindOnBound'
                |}""".stripMargin, cause)
   }

   def bound (st: Tcp.ServerBinding): Receive = {
      case Director.Command.UnbindByDemand(requestor) =>

         if (logger.isDebugEnabled()) logger debug "{'subject': 'Socket.Unbind'}"

         unbindRequestors += requestor
         unbound(st)
   }

   def unbinding: Receive = {
      case Director.State.Unbound =>

         if (logger.isInfoEnabled()) logger info
            s"""{
                |'subject': 'Socket.Unbound',
                |'host': '${config.host}:${config.port}'
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
      if (logger.isDebugEnabled()) logger debug "{'subject': 'Socket.PreStart'}"
      tcpFlow.run.map {
         Right(_)
      }.recover {
         case cause => Left(cause)
      }.foreach(st => self ! st)
      if (logger.isDebugEnabled()) logger debug "{'subject': 'Socket.Binding'}"
   } // Async !

   override def postStop (): Unit = synchronized {
      if (logger.isDebugEnabled()) logger debug "{'subject': 'Socket.PostStop'}"
      Await.ready(bindingSt.unbind(), 3 seconds)
      if (logger.isInfoEnabled()) logger info "{'subject': 'Socket.Unbound'}"
   }

}

private[implv1] object SocketManager {
   def props (
      config: TCPConfigForServer,
      connectionManager: ActorRef,
      requestFlow: Flow[ByteString, ByteString, ActorRef],
      logger: Logger
   ) = Props {
      new SocketManager(config, connectionManager, requestFlow, logger)
   }
}
