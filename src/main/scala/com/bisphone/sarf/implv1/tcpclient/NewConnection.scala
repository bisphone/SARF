package com.bisphone.sarf.implv1.tcpclient

import scala.reflect.ClassTag

import akka.actor.{Actor, ActorRef}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.bisphone.sarf.{TrackedFrame, UntrackedFrame}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient}

object NewConnection {

    case class Config(
        name: String,
        config: TCPConfigForClient,
        stream: StreamConfig
    )

    sealed trait State { def config: Config }
    case class Connecting(ref: ActorRef, override val config: Config) extends State
    case class Connected(ref: ActorRef, override val config: Config) extends State
    case class Disconnected(ref: ActorRef, override val config: Config) extends State

}

class NewConnection [
    Fr <: TrackedFrame,
    UFr <: UntrackedFrame[Fr]
] (
    config: NewDirector.Config
)(
    implicit
    $frame$tracked$tag: ClassTag[Fr],
    $frame4untracked$tag: ClassTag[UFr]
) extends Actor {

    Source.actorPublisher()
    val source = Source.queue[ByteString](100, OverflowStrategy.backpressure)

    def connecting(): Receive = {
        case
    }

    override def receive: Receive = ???

}
