package com.bisphone.sarf.implv2.tcpclient

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.bisphone.std._
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Source, Tcp}
import akka.util.ByteString
import com.bisphone.launcher.Module
import com.bisphone.sarf.{FrameReader, FrameWriter, TrackedFrame, UntrackedFrame}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient}
import Connection._
import com.bisphone.sarf.implv2.tcpclient.Connection.State.{Trying, Unstablished}

object Connection {

    case class Config(
        name: String,
        tcp: TCPConfigForClient,
        stream: StreamConfig
    )

    sealed trait State {
        def id: Int
        def config: Config
        def ref: ActorRef
    }

    object State {
        case class Trying(
            override val id: Int,
            override val ref: ActorRef,
            override val config: Config,
            startedFrom: Long
        ) extends State

        case class Established(
            override val id: Int,
            override val ref: ActorRef,
            override val config: Config,
            at: Long
        ) extends State

        case class Unstablished(
            override val id: Int,
            override val ref: ActorRef,
            override val config: Config,
            message: String,
            prevState: State
        ) extends State
    }


    case class EstablishedOutput(ref: ActorRef)
    case class EstablishedInput(ref: ActorRef)
    case class Gate(in: ActorRef, out: ActorRef)
    case class TimedOut(startedAt: Long)
    case class Send[T <: TrackedFrame](frame: T)
    case class Recieved[T <: TrackedFrame](frame: T)


    def props[T <: TrackedFrame, U <: UntrackedFrame[T]](
        director: ActorRef, id: Int, config: Config,
        writer: FrameWriter[T, U],
        reader: FrameReader[T],
        materializer: Materializer,
        executionContextExecutor: ExecutionContextExecutor
    )(
        implicit
        $tracked: ClassTag[T],
        $untracked: ClassTag[U]
    ): Props = Props { new Connection(
        director, id, config,
        writer, reader,
        materializer,
        executionContextExecutor
    ) }


}

class Connection [
T <: TrackedFrame,
U <: UntrackedFrame[T]
] (
    director: ActorRef,
    id: Int,
    config: Connection.Config,
    writer: FrameWriter[T, U],
    reader: FrameReader[T],
    materializer: Materializer,
    executionContext: ExecutionContextExecutor
)(
    implicit
    $tracked: ClassTag[T],
    $untracked: ClassTag[U]
) extends Actor with Module {

    val name = config.name

    val logger = loadLogger

    def trying(st: State.Trying, income: Option[ActorRef], outgo: Option[ActorRef]): Receive = {

        logger debug s"Trying, Name: ${config.name}, Host: ${config.tcp.host}, Port: ${config.tcp.port}, Income: ${income}, Outgo: ${outgo}"

        /*(income, outgo) match {
            case (Some(in), Some(out)) =>
                established(
                    State.Established(id, self, config, System.currentTimeMillis()),
                    Connection.Gate(in, out)
                )
            case (in, out) => procTrying(st, in, out)
        }*/

        procTrying(st, income, outgo)
    }

    def procTrying(st: State.Trying, income: Option[ActorRef], outgo: Option[ActorRef]): Receive = {
        case EstablishedInput(ref) =>
            logger debug s"Trying, EstablishedInput: ${ref}, Self: ${self}"
            context become trying(st, ref.some, outgo)
        case EstablishedOutput(ref) =>
            logger debug s"Trying, EstablishedOutput: ${ref}, Self: ${self}"
            context become trying(st, income, ref.some)
        case TimedOut(startedAt) =>
            logger debug s"Trying, TimedOut!, Stopping"
            context stop self
        case st: State.Established if income.isDefined && outgo.isDefined =>
            context become established(st, Gate(in = income.get, out = outgo.get))
        case st: State.Established =>
            throw new IllegalStateException("Established with out In & Out Registeration!")
    }

    def established(st: State.Established, gate: Gate): Receive = {
        logger info s"Established Connection, Name: ${config.name}, Host: ${config.tcp.host}, Port: ${config.tcp.port}"
        context watch gate.in
        context watch gate.out
        logger debug s"Established Connection, Config: ${config}, Self: ${self}, Input: ${gate.in}, Output: ${gate.out}, Watching Input & Output!"
        director ! st
        procEstablished(st, gate)
    }

    def procEstablished(st: State.Established, gate: Gate): Receive = {

        case Send(frame) =>
            // @todo: Implement BackPresure !
            logger trace s"Send , TrackingKey: ${frame.trackingKey}, TypeKey: ${frame.dispatchKey.typeKey}"
            gate.out ! frame

        case Recieved(frame) =>
            // @todo: Implement BackPresure !
            logger trace s"Recieve, TrackingKey: ${frame.trackingKey}, TypeKey: ${frame.dispatchKey.typeKey}"
            director ! frame

        case Terminated(worker) if worker == gate.in =>
            logger debug s"Established, Input Termination, Input: ${worker}, Stopping"
            context stop self


        case Terminated(worker) if worker == gate.out =>
            logger debug s"Established, Output Termination, Input: ${worker}, Stopping"
            context stop self
    }

    override def receive: Receive = trying(State.Trying(id, self, config, System.currentTimeMillis), None, None)

    override def preStart(): Unit = {

        super.preStart()

        val flow = ConnectionFlow(s"${name}.flow", config.stream, self, reader, config.stream.byteOrder)

        Tcp()(context.system).outgoingConnection(config.tcp.host, config.tcp.port)
            .joinMat(flow) {
                case (outgoing, _) => outgoing.map { i =>
                    logger debug s"Outgoing, ${i}"
                    self ! State.Established(id, self, config, System.currentTimeMillis)
                }(executionContext)
            }.run()(materializer).recover{
                case NonFatal(cause) =>
                    logger error (s"Can't establish connection!",cause)
                    context stop self
            }(executionContext)

        context.system.scheduler.scheduleOnce(
            config.tcp.connectingTimeout, self, TimedOut
        )(executionContext)

        logger debug "PreStart"
    }

    override def postStop(): Unit = {
        logger debug s"PostStop, Self: ${self}, Config: ${config}"
        super.postStop
    }

}

