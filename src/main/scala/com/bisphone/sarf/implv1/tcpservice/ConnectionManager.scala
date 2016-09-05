package com.bisphone.sarf.implv1.tcpservice

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Terminated}
import akka.stream.scaladsl.Tcp
import org.slf4j.Logger

import scala.collection.mutable

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class ConnectionManager(logger: Logger) extends Actor {

  private val dict = mutable.HashMap.empty[ActorRef, Tcp.IncomingConnection]

  private def addr2str(addr: InetSocketAddress): String = s"${addr.getHostName}:${addr.getPort}"

  def receive: Receive = {

    case Director.Event.NewConnection(ref, actor) =>

      dict(actor) = ref
      context watch actor

      if (logger.isInfoEnabled()) logger info
        s"""{
            |'subject': 'Connections.New',
            |'host': '${addr2str(ref.localAddress)}',
            |'remote': ${addr2str(ref.remoteAddress)},
            |'totalConnections': ${dict.size}
            |}""".stripMargin

    case Terminated(actor) if dict contains actor =>

      val Some(ref) = dict remove actor

      if (logger.isInfoEnabled()) logger info
        s"""{
            |'subject': 'Connections.Closed',
            |'host': '${addr2str(ref.localAddress)}',
            |'remote': '${addr2str(ref.remoteAddress)}',
            |'actor': '${actor}',
            |'totalConnections': ${dict.size}
            |}""".stripMargin
  }

  override def preStart(): Unit = {
    if (logger.isDebugEnabled()) logger debug "{'subject': 'Connections.PreStart'}"
  }

  override def postStop(): Unit = {
    if (logger.isDebugEnabled()) logger debug "{'subject': 'Connections.PostStop'}"
    dict.keys.foreach {
      _ ! PoisonPill
    }
  }

}

private[implv1] object ConnectionManager {
  def props(logger: Logger) = Props {
    new ConnectionManager(logger)
  }
}