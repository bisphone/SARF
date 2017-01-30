package util

import akka.util.ByteString
import com.bisphone.sarf._
import com.bisphone.util.ByteOrder

import scala.concurrent.Future

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
object TextFrame {

    val order = ByteOrder.BigEndian
    implicit val javaValueForByteOrder = order.javaValue

    case class Tracked (
        override val dispatchKey: TypeKey[_],
        val content: ByteString,
        override val trackingKey: Int
    ) extends TrackedFrame {
        override val bytes: ByteString =
            ByteString.newBuilder
                .putInt(dispatchKey.typeKey)
                .putInt(trackingKey)
                .append(content)
                .result()
    }

    case class Untracked (
        override val dispatchKey: TypeKey[_],
        bytes: ByteString
    ) extends UntrackedFrame[Tracked]

    def writer = new FrameWriter[Tracked, Untracked] {
        def writeFrame (uf: Untracked, tk: Int): Tracked = {
            Tracked(uf.dispatchKey, uf.bytes, tk)
        }
    }

    val reader = new FrameReader[Tracked] {
        def readFrame (bytes: ByteString): Tracked = {
            val iter = bytes.iterator
            val key = order.decodeInt(iter, 4)
            val tk = order.decodeInt(iter, 4)
            val content = ByteString(iter.toArray)
            Tracked(TypeKey(key), content, tk)
        }
    }

    implicit val stringKey = TypeKey[String](0)

    implicit val stringWriter = new Writer[String, Tracked, Untracked] {
        def write (str: String): Untracked = Untracked(stringKey, ByteString(str))
    }

    implicit val stringReader = new Reader[String, Tracked] {
        override def read (frm: Tracked): String = new String(frm.content.toArray)
    }

    implicit val intKey = TypeKey[Int](1)

    implicit val intWriter = new Writer[Int, Tracked, Untracked] {
        override def write (int: Int): Untracked =
            Untracked(intKey, ByteString.newBuilder.putInt(int).result())
    }

    implicit val intReader = new Reader[Int, Tracked] {
        override def read (frm: Tracked): Int = order.decodeInt(frm.content.iterator, 4)
    }

    val failureHandler = new FailureHandler {
        def apply (cause: Throwable, bytes: ByteString): Future[IOCommand] = {
            val frame = reader.readFrame(bytes)
            val utr = Untracked(intKey, ByteString.newBuilder.putInt(-1).result())
            val cmd = IOCommand.Send(writer.writeFrame(utr, frame.trackingKey).bytes)
            Future successful cmd
        }
    }

    implicit val statTag = StatTag[String]("String")

}
