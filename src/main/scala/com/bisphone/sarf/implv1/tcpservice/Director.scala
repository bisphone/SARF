package com.bisphone.sarf.implv1.tcpservice

import akka.actor.{Actor, ActorRef, AllForOneStrategy, Props, SupervisorStrategy}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForServer}

import scala.concurrent.Future
import akka.util.ByteString
import com.bisphone.sarf.IOCommand
import org.slf4j.LoggerFactory
import akka.stream.scaladsl.Tcp

import scala.concurrent.duration._

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
private[implv1] class Director (
   name: String,
   tcp: TCPConfigForServer,
   stream: StreamConfig,
   debug: Boolean,
   onRequest: ByteString => Future[IOCommand]
) extends Actor {

   val loggerName = s"SARFServer(${name}).Director"
   val logger = LoggerFactory getLogger loggerName

   var st: Director.State = Director.State.Binding

   override val supervisorStrategy = AllForOneStrategy(
      maxNrOfRetries = 3,
      withinTimeRange = 5 minutes,
      loggingEnabled = true
   ) {
      case cause: Director.Exception.SocketBindFailure => SupervisorStrategy.Restart
   }

   override def preStart (): Unit = {

      val flow =
         RequestFlowStream(
            name,
            stream,
            ConnectionAgent.props(logger),
            logger,
            debug
         )(onRequest)

      val connectionManager: ActorRef = context.actorOf(
         ConnectionManager.props(name),
         "connections"
      )

      val socketManager: ActorRef = context.actorOf(
         SocketManager.props(name, tcp, connectionManager, flow),
         "socket"
      )

      context become initialized(connectionManager, socketManager)

      if (logger isWarnEnabled()) logger warn
          s"""{
             |"subject":      "${loggerName}.preStart",
             |"actor":        "${self}"
             |}""".stripMargin
   }

   override def postStop (): Unit = stop

   val initializing: Receive = {
      case Director.Command.GetState => sender ! st
   }

   def initialized (
      connectionManager: ActorRef,
      socketManger: ActorRef
   ): Receive = {

      case Director.Command.GetState => sender ! st

      case st: Director.State => updateSt(st)

      case Director.Command.Unbind =>
         socketManger ! Director.Command.UnbindByDemand(sender)

      case Director.Event.UnboundByDemand(requestors) =>
         requestors.foreach {
            _ ! Director.State.Unbound
         }
         stop
   }

   def receive: Receive = initializing

   def updateSt (st: Director.State): Unit = {
      val old = this.st
      this.st = st
      if (logger.isInfoEnabled()) logger info
         s"""{
             |"subject":      "${loggerName}.ChangeState",
             |"oldState":     "${old}",
             |"newState":     "${st}"
             |}""".stripMargin
   }

   def stop = {
      if (logger isWarnEnabled ()) logger warn s"""{"subject": "${loggerName}.postStop"}"""
      try context stop self finally ();
   }

}

private[implv1] object Director {

   def props (
      name: String,
      tcp: TCPConfigForServer,
      stream: StreamConfig,
      debug: Boolean
   )(
      onRequest: ByteString => Future[IOCommand]
   ) = Props {
      new Director(name, tcp, stream, debug, onRequest)
   }

   trait State

   object State {

      case object Binding extends State

      case class Bound (ref: Tcp.ServerBinding) extends State

      case object Unbinding extends State

      case object Unbound extends State

   }

   trait Command

   object Command {

      case object Unbind extends Command

      case class UnbindByDemand (requestor: ActorRef) extends Command

      case object GetState extends Command

   }

   trait Event

   object Event {

      case class NewConnection (
         ref: Tcp.IncomingConnection,
         director: ActorRef
      ) extends Event

      case class UnboundByDemand (requestors: List[ActorRef]) extends Event

   }

   sealed class Exception (
      msg: String,
      cause: Throwable = null
   ) extends RuntimeException(msg, cause)

   object Exception {

      class SocketBindFailure (
         msg: String,
         cause: Throwable = null
      ) extends Director.Exception(msg, cause)

   }

}
