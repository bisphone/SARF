package com.bisphone.sarf.implv1.tcpclient

import scala.reflect.ClassTag

import akka.actor.{Actor, ActorRef}
import com.bisphone.sarf.{TrackedFrame, UntrackedFrame}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient}
import NewDirector._

object NewDirector {

    case class ConnectionConfig(
        name: String,
        tcp: Seq[TCPConfigForClient],
        stream: StreamConfig
    )

    sealed trait Connection { def config: ConnectionConfig }

    case class Connected(ref: ActorRef, override val config: ConnectionConfig) extends Connection
    case class Connecting(ref: ActorRef, override val config: ConnectionConfig) extends Connection
    case class Unexpected(ref: ActorRef, override val config: ConnectionConfig) extends Connection

    case class Config(
        connections: Seq[ConnectionConfig],
        minEstablishedConnection: Int
    )

}

class NewDirector[
    Fr <: TrackedFrame,
    UFr <: UntrackedFrame[Fr]
] (
    name: String,
    config: NewDirector.Config
)(
    implicit
    $frame$tracked$tag: ClassTag[Fr],
    $frame4untracked$tag: ClassTag[UFr]
) extends Actor {


    def initializing(
        connected: Seq[Connected],
        connecting: Seq[Connecting],
        unexp: Seq[Unexpected]
    ): Receive {
        case
    }

    override def receive: Receive = ???



}
