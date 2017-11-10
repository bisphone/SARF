package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient}
import org.slf4j.LoggerFactory
import akka.stream.scaladsl.{Flow, Tcp}
import com.bisphone.sarf.{FrameReader, FrameWriter, TrackedFrame, UntrackedFrame}
import com.bisphone.util._
import com.bisphone.std._

import scala.reflect.{ClassTag, classTag}

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  *
  *         Inject TrackingKey and Write Frame
  */

private[implv1] object Director {

    def props[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
        name: String,
        tcp: TCPConfigForClient,
        stream: StreamConfig,
        writer: FrameWriter[Fr, UFr],
        reader: FrameReader[Fr]
    )(
        implicit
        fr$tag: ClassTag[Fr],
        uf$tag: ClassTag[UFr]
    ) = Props {
        new Director(name, tcp, stream, writer, reader)
    }

    // Controlling & Debug & Health Issue
    private[implv1] case class Echo[T] (value: T)

    private[implv1] trait Command

    private[implv1] object Command {

        case class Send[T] (t: T) extends Command

        case object Disconnect extends Command

        case object GetState extends Command

    }

    private[implv1] trait State

    private[implv1] object State {

        case object Connecting extends State

        case object Connected extends State

        case object Disconnecting extends State

        case object Discountected extends State

    }

    private[implv1] trait Event

    private[implv1] object Event {

        case class Received[T] (t: T) extends Event

        case class Connected (
            conn: Tcp.OutgoingConnection,
            publisher: ActorRef,
            consumer: ActorRef
        ) extends Event

        case class Failed (cause: Option[Throwable] = None)

        // case object StreamHasClosed extends Event

        // case class StreamHasFailed (cause: Throwable) extends Event

        case class Expired (sub: String) extends Event

        // case class Sent(trackingKey: Int) extends Event
        case object CantSend extends Event

    }

    private[implv1] case class TrackedRequest (
        trackingKey: Int,
        requestor: ActorRef,
        at: Long
    )

    private[implv1] class Tracker {
        private var count = 0
        private val frames = scala.collection.mutable.HashMap.empty[Int, Director.TrackedRequest]

        private def now () = System.currentTimeMillis()

        def trackingKey (requestor: ActorRef): Int = {
            count += 1
            val tk = count
            frames(tk) = TrackedRequest(tk, requestor, now())
            tk
        }

        def resolve (tk: Int): Option[ActorRef] = frames.remove(tk).map(_.requestor)
    }

    sealed class Exception (msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

    class IOException (msg: String, cause: Throwable = null) extends this.Exception(msg, cause)

    class UnrequestedResponse[Rs] (
        val response: Rs,
        msg: String, cause: Throwable = null
    ) extends this.Exception(msg, cause)

}

private[implv1] class Director[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
    name: String,
    tcp: TCPConfigForClient,
    stream: StreamConfig,
    writer: FrameWriter[Fr, UFr],
    reader: FrameReader[Fr]
)(
    implicit
    fr$tag: ClassTag[Fr],
    uf$tag: ClassTag[UFr]
) extends Actor {

    import Director._

    val unit = ()

    implicit val ec = context.dispatcher

    implicit val mat = ActorMaterializer()(context.system)

    val loggerName = s"${name}.sarf.client.director"
    val logger = LoggerFactory getLogger loggerName

    val debug = logger.isDebugEnabled()

    val tracker = new Director.Tracker

    var state: State = State.Connecting

    val disconnectRequesters = scala.collection.mutable.ListBuffer.empty[ActorRef]

    def changeContext(name: String, ctx: Receive) = {
        if (logger.isDebugEnabled()) logger debug s"Contxt Change: ${name}"
        context become ctx
    }

    def connecting: Receive = {
        state = State.Connecting

        {
        case Command.GetState =>
            if (logger.isDebugEnabled) logger debug s"Conneting, GetState!, Sender: ${sender}"
            sender ! state


        // case frm: Command.Send[_] =>
        // logger warn """{'subject': 'Director(connecting).Send => Can\'tSend'}"""
        case Command.Send(rq: UFr) if uf$tag.unapply(rq).isDefined /* check type erasaure */ =>

            if (logger.isWarnEnabled) logger warn s"Connecting, Send, Sender: ${sender}, TypeKey: ${rq.dispatchKey}"

            sender ! Director.Event.CantSend

        case ev: Event.Connected =>

            if (logger.isInfoEnabled) logger info s"Connecting, Established"
            if (logger.isDebugEnabled()) logger debug s"Connecting, Established, Publisher: ${ev.publisher}, Consumer: ${ev.consumer}, ${ev.conn}"

            context watch ev.publisher
            context watch ev.consumer
            changeContext("Connecting -> Connected", connected(ev)) // context become connected(ev)

        case Event.Expired(_) =>

            if (logger.isErrorEnabled) logger error s"Connecting, Expired, Stoping!"

            context stop self

        case Event.Failed(cause) =>
            cause.foreach(_.printStackTrace())
            if (logger.isErrorEnabled) logger error (s"Connecting, Failure, TCP Config: ${tcp}", cause)

            context stop self
    }}

    def connected (ev: Event.Connected): Receive = {
        state = State.Connected

        {
            case Command.GetState =>
                if (logger.isDebugEnabled) logger debug s"Connected, GetState!, Sender: ${sender}"
                sender ! state

            case Command.Send(rq: UFr) if uf$tag.unapply(rq).isDefined /* check type erasaure */ =>

                val tk = tracker.trackingKey(sender())
                val frame = writer.writeFrame(rq, tk)

                ev.publisher ! frame.bytes

                if (logger.isDebugEnabled()) logger debug s"Connected, Send, TypeKey: ${frame.dispatchKey}, TrackingKey: ${tk}, Bytes: ${frame.bytes.size}"

            case Event.Received(bytes: ByteString) =>

                val frame = reader.readFrame(bytes)

                tracker.resolve(frame.trackingKey) match {
                    case Some(requestor) =>

                        if (logger.isDebugEnabled) logger debug s"Connected, Received, TypeKey: ${frame.dispatchKey}, TrackingKey: ${frame.trackingKey}, Bytes: ${frame.bytes.size}"

                        requestor ! Event.Received[Fr](frame)

                    case None =>
                        if (logger.isErrorEnabled()) logger error s"Connected, Received, Undefined TrackingKey : ${frame.trackingKey}, TypeKey: ${frame.dispatchKey}, Bytes: ${frame.bytes.size}"
                        // Why?
                        // throw new Director.UnrequestedResponse[Fr](frame, s"Unrequested Response trackingKey: ${frame.trackingKey}")
                }

            case Command.Disconnect =>

                if (logger.isWarnEnabled()) logger warn s"Connected, Disconnecting, Sender: ${sender()}"

                ev.publisher ! PoisonPill

                disconnectRequesters += sender()
                context.system.scheduler.scheduleOnce(tcp.connectingTimeout, self, Event.Expired("Disconnecting"))
                changeContext("Connected -> Disconnecting", disconnecting) // context become disconnecting

            case Terminated(ref) =>

                if (logger.isErrorEnabled()) logger error s"Connected, Terminated, Origin: ${ref}, Self: ${self}"

                throw new Director.IOException(s"the ${ref} has been terminated!")

            case Echo(value) => sender ! value
                if (logger.isDebugEnabled()) logger debug s"Connected, Echo, Content: ${value}, Sender: ${sender}"
        }
    }

    def disconnecting: Receive = {
        state = State.Disconnecting

        {

            case Command.Disconnect =>

                if (logger.isDebugEnabled()) s"Disconnecting, GetState!, Sender: ${sender()}"

                disconnectRequesters += sender()

            case Terminated(ref) =>

                if (logger.isWarnEnabled()) logger warn s"Disconnecting, Terminated, Origin: ${ref}, Self: ${self}"

                disconnectRequesters.foreach {
                    _ ! unit
                }

                context stop self

            case Event.Expired(_) =>

                if (logger.isWarnEnabled()) logger warn s"Disconnecting, Expired!"

                disconnectRequesters.foreach {
                    _ ! unit
                }

                context stop self
        }
    }

    def receive: Receive = connecting

    override def preStart (): Unit = {

        if (logger.isInfoEnabled()) logger info s"PreStart, Self: ${self}, State: ${state}, Tracker: ${tracker}"

        val publisher: Props = Writer.props(name, self)
        val consumer: Props = Reader.props(name, self)

        if (logger.isDebugEnabled()) logger debug s"PreStart, Publisher: ${publisher}, Consumer: ${consumer}"

        val flow: Flow[ByteString, ByteString, (ActorRef, ActorRef)] =
            ClientFlow(
                name,
                stream,
                publisher,
                consumer,
                logger,
                debug
            )

        if (logger.isDebugEnabled()) logger debug s"PreStart, TCP Config: ${tcp}, Trying"

        Tcp()(context.system).outgoingConnection(tcp.host, tcp.port)
            .joinMat(flow) {
                case (outgoing, (consumer, publisher)) => outgoing.map { i =>
                    if (logger.isDebugEnabled) logger debug s"PreStart, TCP Config: ${tcp}, Connected, Publisher: ${publisher}, Consumer: ${consumer}"
                    Event.Connected(i, publisher, consumer)
                }
            }.run.recover {
                case cause =>
                    if (logger.isErrorEnabled()) logger error (s"PreStart, TCP Config: ${tcp}, Failed", cause)
                    Event.Failed(Some(cause))
            }.foreach { st => self ! st }

        if (logger.isDebugEnabled()) logger debug s"PreStart, Schedule Timeout: ${tcp.connectingTimeout}"
        context.system.scheduler.scheduleOnce(tcp.connectingTimeout, self, Event.Expired("Connecting"))
    }

    override def postStop (): Unit = try {
        if (logger.isInfoEnabled) logger info s"PostStop, Self: ${self}, State: ${state}, Tracker: ${tracker}"
    } catch {
        case cause: Throwable =>
            logger error (s"PostStop, Failure", cause)
    }

}
