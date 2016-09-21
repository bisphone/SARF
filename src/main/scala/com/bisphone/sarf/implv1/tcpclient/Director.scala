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
    reader: FrameReader[Fr],
    debug: Boolean
  )(
    implicit
    fr$tag: ClassTag[Fr],
    uf$tag: ClassTag[UFr]
  ) = Props {
    new Director(name, tcp, stream, writer, reader, debug)
  }

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

    case class Received[T](t: T) extends Event

    case class Connected (
      conn: Tcp.OutgoingConnection,
      publisher: ActorRef,
      consumer: ActorRef
    ) extends Event

    case object StreamHasClosed extends Event

    case class StreamHasFailed (cause: Throwable) extends Event

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

  sealed class Exception(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)
  class IOException (msg: String, cause: Throwable = null) extends this.Exception(msg, cause)
  class UnrequestedResponse[Rs](
    val response: Rs,
    msg: String, cause: Throwable= null
  ) extends this.Exception(msg, cause)

}

private[implv1] class Director[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
  name: String,
  tcp: TCPConfigForClient,
  stream: StreamConfig,
  writer: FrameWriter[Fr, UFr],
  reader: FrameReader[Fr],
  debug: Boolean
)(
  implicit
  fr$tag: ClassTag[Fr],
  uf$tag: ClassTag[UFr]
) extends Actor {

  import Director._

  val unit = ()

  implicit val ec = context.dispatcher

  implicit val mat = ActorMaterializer()(context.system)

  val logger = LoggerFactory getLogger name

  val tracker = new Director.Tracker

  var state: State = State.Connecting

  val disconnectRequesters = scala.collection.mutable.ListBuffer.empty[ActorRef]

  def connecting: Receive = {

    case Command.GetState => sender ! state

    case _: Command.Send[_] =>
      logger warn """{'subject': 'Director(connecting).Send => Can\'tSend'}"""
      sender ! Director.Event.CantSend

    case ev: Event.Connected =>

      logger info
        s"""{
            |'subject': 'Director(connecting).Connected'
            |'publisher': '${ev.publisher}',
            |'consumer': '${ev.consumer}',
            |'connection': '${ev.conn}',
            |}""".stripMargin

      context watch ev.publisher
      context watch ev.consumer
      context become connected(ev)

    case Event.Expired(_) =>
      logger warn
        s"""{
            |'subject': 'Director(connecting).Expired => Stop',
            |'tcpConfig': '$tcp'
            |}""".stripMargin
      context stop self
  }

  def connected (ev: Event.Connected): Receive = {

    case Command.GetState => sender ! state

    case Command.Send(rq: UFr) if uf$tag.unapply(rq).isDefined /* check type erasaure */ =>

      val tk = tracker.trackingKey(sender())
      val frame = writer.writeFrame(rq, tk)

      ev.publisher ! frame.bytes

      if (logger.isDebugEnabled()) logger debug
        s"""{
            |'subject': 'Director(connected).Send'
            |'trackingCode': $tk,
            |'bytes': '${frame.bytes.size}'
            |}""".stripMargin

    case Event.Received(bytes:  ByteString) =>

      val frame = reader.readFrame(bytes)
      tracker.resolve(frame.trackingKey) match {
        case Some(requestor) => requestor ! Event.Received[Fr](frame)
        case None => throw new Director.UnrequestedResponse[Fr](frame, s"Unrequested Response trackingKey: ${frame.trackingKey}")
      }

    case Command.Disconnect =>

      logger info "{'subject': 'Director(connected).Disconnect'}"

      ev.publisher ! PoisonPill

      disconnectRequesters += sender()
      context.system.scheduler.scheduleOnce(tcp.connectingTimeout, self, Event.Expired("Disconnecting"))
      context become disconnecting

    case Terminated(ref) =>

      logger warn
        s"""{
            |'subject': 'Director(connected).Terminated => Director.IOException',
            |'actor': '$ref'
            |}""".stripMargin

      throw new Director.IOException(s"the $ref has been terminated!")
  }

  def disconnecting: Receive = {

    case Command.Disconnect =>

      if (logger.isErrorEnabled()) logger debug "{'subject': 'Director(disconnecting).Disconnect'}"

      disconnectRequesters += sender()

    case Terminated(ref) =>

      logger info
        s"""{
            |'subject': 'Director(connected).Terminated',
            |'actor': '$ref'
            |}""".stripMargin

      disconnectRequesters.foreach {
        _ ! unit
      }

      context stop self

    case Event.Expired(_) =>

      logger info "{'subject': 'Director(connected).Expired'}"

      disconnectRequesters.foreach {
        _ ! unit
      }

      context stop self
  }

  def receive: Receive = connecting

  override def preStart (): Unit = {

    val publisher: Props = Writer.props(self)
    val consumer: Props = Reader.props(self)

    val flow: Flow[ByteString, ByteString, (ActorRef, ActorRef)] =
      ClientFlow(
        name,
        stream,
        publisher,
        consumer,
        logger,
        debug
      )

    Tcp()(context.system).outgoingConnection(tcp.host, tcp.port)
      .joinMat(flow) {
        case (outgoing, (consumer, publisher)) => outgoing.map(Event.Connected(_, publisher, consumer))
      }.run.recover {
      case cause => ??? // @todo
    }.foreach { st => self ! st }

    context.system.scheduler.scheduleOnce(tcp.connectingTimeout, self, Event.Expired("Connecting"))
  }

}
