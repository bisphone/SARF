package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable
import akka.NotUsed
import akka.actor.{ ActorLogging, ActorRef, Props, Terminated }
import akka.stream._
import akka.stream.actor.{ ActorPublisher, ActorPublisherMessage }
import akka.util.ByteString
import ActorPublisherMessage._
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.stage._
import akka.stream.stage.GraphStageLogic.StageActor
import com.bisphone.akkastream.ByteStreamSlicer
import com.bisphone.launcher.Module
import com.bisphone.sarf.implv1.tcpclient.ClientFlow.slicer
import com.bisphone.sarf.{ Constant, FrameReader, FrameWriter, TrackedFrame }
import com.bisphone.sarf.implv1.util.StreamConfig
import com.bisphone.util.ByteOrder
import org.slf4j.{ Logger, LoggerFactory }
import sun.reflect.generics.tree.ByteSignature


class Output[T <: TrackedFrame](
    override val name: String,
    connection: ActorRef
) extends GraphStage[SourceShape[ByteString]] with Module {

    val logger = loadLogger

    val out: Outlet[ByteString] = Outlet(name)

    logger debug s"New, ${getClass.getName}"

    class Logic(override val name: String, shape: Shape) extends GraphStageLogic(shape) with Module {

        val logger = loadLogger

        private lazy val self = {
            val actor = getStageActor(onMessage)
            logger debug s"New, Connection: ${connection}, Self: ${actor.ref}"
            actor
        }

        private val messages = mutable.Queue[T]()

        setHandler(out, new OutHandler {
            override def onPull(): Unit = pump()
        })

        private def pump() = {
            if (isAvailable(out) && messages.nonEmpty) {
                val pack = messages.dequeue.bytes
                logger.trace(s"Pump, Bytes: ${pack.size}")
                push(out, pack)
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
                logger trace s"Queue, TrackingKey: ${msg.trackingKey}, TypeKey: ${msg.dispatchKey.typeKey}, Bytes: ${msg.bytes.size}"
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

        /*
[ERROR] [11/15/2017 13:36:42.047] [default-akka.actor.default-dispatcher-6] [akka://default/user/client/$b] not yet initialized: only setHandler is allowed in GraphStageLogic constructor
akka.actor.ActorInitializationException: akka://default/user/client/$b: exception during creation
        at akka.actor.ActorInitializationException$.apply(Actor.scala:193)
        at akka.actor.ActorCell.create(ActorCell.scala:608)
        at akka.actor.ActorCell.invokeAll$1(ActorCell.scala:462)
        at akka.actor.ActorCell.systemInvoke(ActorCell.scala:484)
        at akka.dispatch.Mailbox.processAllSystemMessages(Mailbox.scala:282)
        at akka.dispatch.Mailbox.run(Mailbox.scala:223)
        at akka.dispatch.Mailbox.exec(Mailbox.scala:234)
        at akka.dispatch.forkjoin.ForkJoinTask.doExec(ForkJoinTask.java:260)
        at akka.dispatch.forkjoin.ForkJoinPool$WorkQueue.runTask(ForkJoinPool.java:1339)
        at akka.dispatch.forkjoin.ForkJoinPool.runWorker(ForkJoinPool.java:1979)
        at akka.dispatch.forkjoin.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:107)
Caused by: java.lang.IllegalStateException: not yet initialized: only setHandler is allowed in GraphStageLogic constructor
        at akka.stream.stage.GraphStageLogic.interpreter(GraphStage.scala:331)
        at akka.stream.stage.GraphStageLogic.getStageActor(GraphStage.scala:1040)
        at com.bisphone.sarf.implv2.tcpclient.Input$Logic.<init>(Flow.scala:101)
        at com.bisphone.sarf.implv2.tcpclient.Input.createLogic(Flow.scala:134)
        at akka.stream.stage.GraphStage.createLogicAndMaterializedValue(GraphStage.scala:95)
        at akka.stream.impl.GraphStageIsland.materializeAtomic(PhasedFusingActorMaterializer.scala:627)
        at akka.stream.impl.PhasedFusingActorMaterializer.materialize(PhasedFusingActorMaterializer.scala:458)
        at akka.stream.impl.PhasedFusingActorMaterializer.materialize(PhasedFusingActorMaterializer.scala:420)
        at akka.stream.impl.PhasedFusingActorMaterializer.materialize(PhasedFusingActorMaterializer.scala:415)
        at akka.stream.scaladsl.RunnableGraph.run(Flow.scala:496)
        at com.bisphone.sarf.implv2.tcpclient.Connection.preStart(Connection.scala:174)
        at akka.actor.Actor$class.aroundPreStart(Actor.scala:528)
        at com.bisphone.sarf.implv2.tcpclient.Connection.aroundPreStart(Connection.scala:85)
        at akka.actor.ActorCell.create(ActorCell.scala:591)
        ... 9 more

Then make it lazy !

         */
        private lazy val self = {
            val actor = getStageActor(onMessage)
            logger debug s"New, LazyCall, Connection: ${connection}, Self: ${actor.ref}"
            actor
        }

        setHandler(in, new InHandler {
            override def onPush(): Unit = {
                val bytes = grab(in)
                val frame = reader readFrame bytes
                logger trace s"OnPush, TrackingKey: ${frame.trackingKey}, TypeKey: ${frame.dispatchKey.typeKey}, Bytes(${bytes.size}): ${bytes}, Connection: ${connection}"
                pull(in)
                connection ! Connection.Recieved(frame)
            }
        })

        override def preStart(): Unit = {
            super.preStart()
            self watch connection
            connection ! Connection.EstablishedInput(self.ref)
            pull(in)
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
        reader: FrameReader[T],
        byteOrder: ByteOrder
    ): Flow[ByteString, ByteString, NotUsed] = {
        val maxSliceSize = config.maxSliceSize
        val byteOrder = config.byteOrder

        val outgoing =
            Source.fromGraph(new Output(s"${name}.output", connection)).map{ bytes =>
                ByteString.newBuilder
                    .putInt(Constant.lenOfLenField + bytes.size)(byteOrder.javaValue)
                    .append(bytes)
                    .result
            }


        val incoming = {

            val slices = {
                Flow.fromGraph(
                    ByteStreamSlicer(s"${name}.slicer", Constant.lenOfLenField, maxSliceSize, byteOrder)
                )
            }.map(_.drop(Constant.lenOfLenField))

            val sink = Sink.fromGraph(new Input(s"${name}.input", connection, reader))

            slices.toMat(sink)(Keep.none)
        }

        Flow.fromSinkAndSourceMat(incoming, outgoing)(Keep.none)
    }
}