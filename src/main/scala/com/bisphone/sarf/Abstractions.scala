package com.bisphone.sarf

import akka.actor.UntypedActor
import com.bisphone.util._
import com.bisphone.std._
import akka.util.ByteString

import scala.annotation.implicitNotFound
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */

object Constant {
   def lenOfLenField: Int = 4
}

// ========================================================================

sealed trait IOCommand

object IOCommand {

   case class Send (bytes: ByteString) extends IOCommand

   case object Close extends IOCommand

   case object KeepGoing extends IOCommand

   case class SendAndClose (bytes: ByteString) extends IOCommand

}

// ========================================================================

@implicitNotFound(
    """
      | Couldn't find implicit value for TypeKey[${T}];
      | May be you are writing unmatched value for 'Err' or 'Out' in 'Func[Err,Out]'
    """)
trait TypeKey[T] extends Serializable {

   def typeKey: Int

   def unapply (other: Int): Boolean = other == typeKey

   def unapply[O <: T] (other: TypeKey[O]): Boolean = unapply(other.typeKey)

   override def toString (): String = s"TypeKey(${getClass.getName}: ${typeKey})"
}

object TypeKey {

   class FreeTypeKey (override val typeKey: Int) extends TypeKey[Nothing]

   def apply[T] (key: Int): TypeKey[T] = new FreeTypeKey(key).asInstanceOf[TypeKey[T]]
}


sealed trait Frame {
   def dispatchKey: TypeKey[_]
}

trait UntrackedFrame[Fr <: TrackedFrame] extends Frame

trait TrackedFrame extends Frame {

   def trackingKey: Int

   def bytes: ByteString

}

// ========================================================================

trait Writer[T, Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] {
   def write (t: T): UFr
}

trait FrameWriter[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] {
   def writeFrame (uf: UFr, trackingKey: Int): Fr
}

trait FrameReader[Fr <: TrackedFrame] {
   def readFrame (bytes: ByteString): Fr
}

trait Reader[T, Fr <: TrackedFrame] {
   def read (t: Fr): T
}

trait Func[E,R] {
   type Error = E
   type Result = R
   type Out = AsyncResult[Error, Result]
}

trait FuncImpl[In, Err, Out] extends (In => AsyncResult[Err, Out])

// ========================================================================

sealed class SARFException (
   subject: String,
   cause: Throwable = null
) extends RuntimeException(subject, cause)

sealed class SARFRemoteException (
   subject: String,
   cause: Throwable = null
) extends SARFException(subject, cause)

sealed class FrameProcessingFailure[Fr <: Frame] (
   val frame: Fr,
   subject: String,
   cause: Throwable = null
) extends SARFException(subject, cause)

class UnsupporetdDispatchKey[Fr <: Frame] (
   frame: Fr,
   subject: String,
   cause: Throwable = null
) extends FrameProcessingFailure(frame, subject, cause)

// ========================================================================

trait FailureHandler
   extends ((Throwable, ByteString) => Future[IOCommand])

trait Service[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]]
   extends (ByteString => Future[IOCommand])

// ========================================================================
// Server

trait TCPServiceRef {

   def isActive: Future[Boolean]

   def shutdown: Future[Unit]

}

// ========================================================================
// Client

trait TCPClientRef[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] {

   def isActive (): Future[Boolean]

   def send (rq: UFr): Future[Fr]

   def close (): Future[Unit]

   def call[Rq, Rs, Er] (rq: Rq)(
      implicit
      rqKey: TypeKey[Rq],
      rsKey: TypeKey[Rs],
      erKey: TypeKey[Er],
      rqWriter: Writer[Rq, Fr, UFr],
      rsReader: Reader[Rs, Fr],
      erReader: Reader[Er, Fr]
   ): AsyncResult[Er, Rs]

   def call[Rq, Rs, Er] (
      rq: Rq,
      rqTC: TypeComplementary[Rq, Fr, UFr],
      rsTC: TypeComplementary[Rs, Fr, UFr],
      erTC: TypeComplementary[Er, Fr, UFr]
   ): AsyncResult[Er, Rs] = call(rq)(
      rqTC.dispatchKey,
      rsTC.dispatchKey,
      erTC.dispatchKey,
      rqTC.writer,
      rsTC.reader,
      erTC.reader
   )

   def apply[Err, Out, Fn <: Func[Err, Out]](
       fn: Fn
   )(
       implicit
       fnKey: TypeKey[Fn],
       fnWriter: Writer[Fn, Fr, UFr],
       errKey: TypeKey[Err],
       errReader: Reader[Err, Fr],
       outKey: TypeKey[Out],
       outReader: Reader[Out, Fr]
   ) = call(fn)(fnKey, outKey, errKey, fnWriter, outReader, errReader)

}

// ========================================================================
// Server Stats

trait StatTag[T] extends Serializable {
   def tag: String

   override def toString (): String = s"StatTag(${getClass.getName}: ${tag})"
}

object StatTag {

   private class FreeStatTag (override val tag: String) extends StatTag[Nothing]

   def apply[T] (tag: String): StatTag[T] = new FreeStatTag(tag).asInstanceOf[StatTag[T]]

   def nothing[T] = apply[T]("nothing")
}

trait StatCollector {

   def done[T] (tag: StatTag[T], duration: Long): Unit

   def failed[T] (tag: StatTag[T], duration: Long)

   def read[T] (tag: StatTag[T], bytes: Long): Unit

   def wrote[T] (tag: StatTag[T], bytes: Long): Unit

}

// ===========================================================================
// Helper

trait TypeComplementary[T, Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] {

   implicit val dispatchKey: TypeKey[T]

   implicit val statTag: StatTag[T]

   implicit val writer: Writer[T, Fr, UFr]

   implicit val reader: Reader[T, Fr]

}
