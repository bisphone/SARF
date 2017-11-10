package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable

import akka.actor.{Actor, ActorRef, Props, Stash, Terminated}
import com.bisphone.launcher.Module
import com.bisphone.sarf.{FrameWriter, TrackedFrame, UntrackedFrame}

object Proxy {

    case class NewConnection(id: Int, name: String, desc: String, ref: ActorRef)
    case class Send[T <: TrackedFrame, U <: UntrackedFrame[T]](frame: U, requestTime: Long)

    def props[T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        director: ActorRef,
        writer: FrameWriter[T, U]
    ): Props = Props { new Proxy(name, director, writer) }

}

class Proxy[T <: TrackedFrame, U <: UntrackedFrame[T]](
    val name: String,
    director: ActorRef,
    writer: FrameWriter[T,U]
) extends Actor with Module {

    val logger = loadLogger

    val conns = mutable.HashMap.empty[Int, ConnectionContext]

    val tracker = new Tracker(s"${name}.tracker")

    val balancer = new RoundRobinConnectionBalancer(s"${name}.balancer")

    val queued = mutable.Queue.empty[RequestContext]

    import Proxy._

    override def receive: Receive = {

        case Proxy.Send(frame: U, time) =>
            balancer.pickOne match {

                case Some(conn) =>
                    val ctx = tracker track (sender, conn)
                    val tracked = writer writeFrame (frame, ctx.trackingKey)
                    conn.ref ! Connection.Send(tracked)

                case None =>
                    logger warn s"NotAvailableConnection!"
                    // @todo stash
            }


        case Connection.Recieved(frame) =>
            tracker.resolve(frame.trackingKey) match {
                case Some(ctx) =>
                    ctx.caller ! frame
                case None =>
                    logger error s"Unrequested Response, TrackingKey: ${frame.trackingKey}"
            }

        case Proxy.NewConnection(id, name, desc, ref) =>
            logger info s"NewConnection, Name: ${name}, Id: ${id}, desc: ${desc}"
            val conn = ConnectionContext(
                name ,desc, id, ref,
                System.currentTimeMillis,
                0, 0, 0
            )
            context watch ref
            balancer add conn

        case Terminated(ref) =>
            (balancer remove ref) match {
                case Some(ctx) =>
                    logger info s"Lost Connection, ID: ${ctx.id}, Name: ${ctx.name}"
                    logger debug s"Lost Connection, ${ctx}"
                case None =>
                    logger warn s"Lossing Connection, UNREGISTERD"
            }

    }

}
