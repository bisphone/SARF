package com.bisphone.sarf.implv1

import com.bisphone.sarf
import com.bisphone.sarf._
import com.bisphone.util._
import com.bisphone.std._

import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import akka.util.ByteString
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */

object Service {

  private[Service] case class Fn[Rq, Rs, Er, Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
    fn: Rq => AsyncResult[Er, Rs],
    rqKey: TypeKey[Rq],
    rsKey: TypeKey[Rs],
    erKey: TypeKey[Er],
    rqReader: Reader[Rq, Fr],
    rsWriter: Writer[Rs, Fr, UFr],
    erWriter: Writer[Er, Fr, UFr],
    statTag: StatTag[Rq]
  ) {
    type rq = Rq
    type rs = Rs
    type er = Er
    val pure: rq => AsyncResult[er, rs] = fn

    def run (frame: Fr)(
      implicit
      ec: ExecutionContextExecutor
    ): Future[UFr] = {

      fn(rqReader.read(frame)).asFuture.map { rsl =>
        rsl match {
          case StdLeft(er) => erWriter.write(er)
          case StdRight(rs) => rsWriter.write(rs)
        }
      }(ec)
    }
  }

  class Builder[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
    executor: ExecutionContextExecutor,
    failureHandler: FailureHandler,
    frameReader: FrameReader[Fr],
    frameWriter: FrameWriter[Fr, UFr],
    statCollector: Option[sarf.StatCollector]
  )(
    implicit
    fr$tag: ClassTag[Fr],
    uf$tag: ClassTag[UFr]
  ) {

    private val fnlist = scala.collection.mutable.ListBuffer.empty[Service.Fn[_, _, _, Fr, UFr]]

    def serve[Rq, Rs, Er] (fn: Rq => AsyncResult[Er, Rs])(
      implicit
      rqKey: TypeKey[Rq],
      rsKey: TypeKey[Rs],
      erKey: TypeKey[Er],
      rqReader: Reader[Rq, Fr],
      rsWriter: Writer[Rs, Fr, UFr],
      erWriter: Writer[Er, Fr, UFr],
      statTag: StatTag[Rq]
    ): Builder[Fr, UFr] = {
      fnlist += Fn[Rq, Rs, Er, Fr, UFr](fn, rqKey, rsKey, erKey, rqReader, rsWriter, erWriter, statTag)
      this
    }

    def result: StdTry[Service[Fr, UFr]] = Try {

      if (fnlist.size < 1)
        throw new RuntimeException("The function list is empty!")

      val fns = fnlist.map(i => i.rqKey.typeKey -> i).toMap[Int, Fn[_, _, _, Fr, UFr]]

      if (fns.size != fnlist.size)
        throw new RuntimeException("Invalid Functions: Some functions has the same input-signature")

      new Service[Fr, UFr](
        executor,
        failureHandler,
        frameReader, frameWriter,
        fns,
        statCollector
      )
    }


  }

}

class Service[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] private(
  executor: ExecutionContextExecutor,
  failureHandler: FailureHandler,
  frameReader: FrameReader[Fr],
  frameWriter: FrameWriter[Fr, UFr],
  handlers: Map[Int, Service.Fn[_, _, _, _, _]],
  statCollector: Option[sarf.StatCollector]
)(
  implicit
  fr$tag: ClassTag[Fr],
  uf$tag: ClassTag[UFr]
) extends sarf.Service[Fr, UFr] {


  protected def get (key: TypeKey[_]): Option[Service.Fn[_, _, _, Fr, UFr]] =
    handlers.get(key.typeKey).asInstanceOf[Option[Service.Fn[_, _, _, Fr, UFr]]]

  protected def run (
    fn: Service.Fn[_, _, _, Fr, UFr], frame: Fr
  ): Future[IOCommand] = {
    fn.run(frame)(executor).map { ufr =>
      IOCommand.Send(frameWriter.writeFrame(ufr, frame.trackingKey).bytes)
    }(executor)
  }

  protected def runWithStat (
    fn: Service.Fn[_, _, _, Fr, UFr], frame: Fr,
    zero: Long, stat: sarf.StatCollector
  ): Future[IOCommand] = {

    val promise = Promise[IOCommand]

    run(fn, frame).onComplete {
      case StdSuccess(iocmd) =>
        if (statCollector.isDefined) statCollector.get.done(fn.statTag, System.currentTimeMillis() - zero)
        promise success iocmd
      case StdFailure(cause) =>
        if (statCollector.isDefined) statCollector.get.failed(fn.statTag, System.currentTimeMillis() - zero)
        promise completeWith failureHandler(cause, frame.bytes)
    }(executor)

    promise.future
  }

  protected def unsupported (frame: Fr) = {
    failureHandler(new UnsupporetdDispatchKey[Fr](
      frame,
      s"Unsupported Dispatch Key: ${frame.dispatchKey} (by TrackingKey: ${frame.trackingKey})"
    ), frame.bytes)
  }

  def handle (bytes: ByteString): Future[IOCommand] = try {


    val frame = frameReader.readFrame(bytes)
    get(frame.dispatchKey) match {
      case Some(fn) =>

        if (statCollector.isDefined) {
          runWithStat(fn, frame, System.currentTimeMillis(), statCollector.get)
        } else run(fn, frame).recoverWith {
          case NonFatal(cause) => failureHandler(cause, frame.bytes)
        }(executor)

      case None =>
        // @todo: Collect Stats
        LoggerFactory.getLogger("OPS").warn(handlers.keys.mkString(","))
        unsupported(frame)
    }

  } catch {
    case NonFatal(cause) =>
      // @todo: Collect Stats
      failureHandler(cause, bytes)
  }

  def apply (bytes: ByteString): Future[IOCommand] = handle(bytes)

}