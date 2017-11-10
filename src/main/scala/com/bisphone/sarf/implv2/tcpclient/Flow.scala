package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, Props, Terminated}
import akka.stream._
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.util.ByteString
import ActorPublisherMessage._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.stage._
import akka.stream.stage.GraphStageLogic.StageActor
import com.bisphone.akkastream.ByteStreamSlicer
import com.bisphone.launcher.Module
import com.bisphone.sarf.{Constant, FrameReader, TrackedFrame}
import com.bisphone.sarf.implv1.util.StreamConfig
import com.bisphone.util.ByteOrder
import org.slf4j.{Logger, LoggerFactory}
import sun.reflect.generics.tree.ByteSignature


class Output[T <: TrackedFrame](
    override val name: String,
    connection: ActorRef
) extends GraphStage[SourceShape[ByteString]] with Module {

    val logger = loadLogger

    val out: Outlet[ByteString] = Outlet(name)

    logger debug "New"

    class Logic(override val name: String, shape: Shape) extends GraphStageLogic(shape) with Module {

        val logger = loadLogger

        private val self = getStageActor(onMessage)

        private val messages = mutable.Queue[T]()

        setHandler(out, new OutHandler {
            override def onPull(): Unit = pump()
        })

        logger debug s"New, Connection: ${connection}, Self: ${self.ref}"

        private def pump() = {
            if (isAvailable(out) && messages.nonEmpty) {
                push(out, messages.dequeue.bytes)
            }
        }

        override def preStart(): Unit = {
            super.preStart()
            self watch connection
            connection ! Connection.EstablishedOutput(self.ref)
            logger debug s"PreStart, Watch & Register Connection: ${connection}, Self: ${self.ref}"
        }

        override def postStop(): Unit = {
            try self unwatch connection finally ();
            logger debug s"PostStop, Stop Watching Connection: ${connection}, Self: ${self.ref}"
            super.postStop()
        }

        private def onMessage(x: (ActorRef, Any)): Unit = x match {
            case (`connection`, msg: T) =>
                messages enqueue msg
                pump()
            case (_, Terminated(`connection`)) =>
                logger debug s"Terminated Connection Acotr, Connection: ${connection}, Self: ${self.ref}, Cancel Stage!"
                fail(out, new RuntimeException(s"Terminated Connection Acotr, Connection: ${connection}, Self: ${self.ref}"))
        }

    }

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogic(
        inheritedAttributes: Attributes
    ): GraphStageLogic = new Logic(s"${name}.logic", shape)
}

class Input[T <: TrackedFrame](
    override val name: String,
    connection: ActorRef,
    reader: FrameReader[T]
) extends GraphStage[SinkShape[ByteString]] with Module {

    val logger = loadLogger

    val in: Inlet[ByteString] = Inlet(name)

    override def shape: SinkShape[ByteString] = SinkShape(in)

    class Logic(override val name: String, shape: Shape) extends GraphStageLogic(shape) with Module {

        val logger = loadLogger

        val self = getStageActor(onMessage)

        setHandler(in, new InHandler {
            override def onPush(): Unit = {
                val frame = reader readFrame grab(in)
                connection ! frame
            }
        })

        logger debug s"New, Connection: ${connection}, Self: ${self.ref}"

        override def preStart(): Unit = {
            super.preStart()
            self watch connection
            connection ! Connection.EstablishedInput(self.ref)
            logger debug s"PreStart, Watch & Register Connection: ${connection}, Self: ${self}"
        }

        override def postStop(): Unit = {
            try self unwatch connection finally ();
            logger debug s"PostStop, Stop Watching Connection: ${connection}, Self: ${self.ref}"
            super.postStop
        }

        def onMessage(x: (ActorRef, Any)): Unit = x match {
            case (_, Terminated(`connection`)) =>
                logger debug s"Terminated Connection Acotr, Connection: ${connection}, Self: ${self.ref}, Cancel Stage!"
                cancel(in)
        }
    }

    override def createLogic(
        inheritedAttributes: Attributes
    ): GraphStageLogic = new Logic(s"${name}.logic", shape)

}

object ConnectionFlow {
    def apply[T <: TrackedFrame](
        name: String,
        config: StreamConfig,
        connection: ActorRef,
        reader: FrameReader[T]
    ): Flow[ByteString, ByteString, NotUsed] = {
        val maxSliceSize = config.maxSliceSize
        val byteOrder = config.byteOrder

        val flow = {
            Flow.fromGraph(
                ByteStreamSlicer(s"${name}.slicer", Constant.lenOfLenField, maxSliceSize, byteOrder)
            )
        }.map(_.drop(Constant.lenOfLenField))

        val outgoing = Source.fromGraph(new Output(s"${name}.output", connection))

        val incoming = Sink.fromGraph(new Input(s"${name}.input", connection, reader))

        Flow.fromSinkAndSourceMat(incoming, outgoing)(Keep.none)
    }
}