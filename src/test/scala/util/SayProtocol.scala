package util

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.ByteString
import com.bisphone.sarf._
import com.bisphone.util.ByteOrder

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
object SayProtocol { self =>

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

    // @todo: write it as a macro
    def writer[T](fn: T => Untracked) = new Writer[T, Tracked, Untracked] {
        override def write(t: T) = fn(t)
    }

    // @todo: write it as a macro
    def reader[T](fn: Tracked => T) = new Reader[T, Tracked] {
        override def read(f: Tracked) = fn(f)
    }

    case class Error(value: String)

    implicit val errorKey = TypeKey[Error](1)

    implicit val errorWriter = writer[Error] { err =>
        Untracked(errorKey, akka.util.ByteString(err.value))
    }

    implicit val errorReder = reader[Error]{ frame =>
        Error(new String(frame.content.toArray))
    }

    case class Hello(name: String)

    implicit val helloKey = TypeKey[Hello](2)

    implicit val helloWriter = writer[Hello] { value =>
        Untracked(helloKey, akka.util.ByteString(value.name))
    }

    implicit val helloReader = reader[Hello] { frame =>
        Hello(new String(frame.content.toArray))
    }

    case class Bye(name: String)

    implicit val byeKey = TypeKey[Bye](3)

    implicit val byeWriter = writer[Bye]{ value =>
        Untracked(byeKey, akka.util.ByteString(value.name))
    }

    implicit val byeReader = reader[Bye] { frame =>
        Bye(new String(frame.content.toArray))
    }

    case class SayHello(to: String) extends Func {
        override type Error = self.Error
        override type Result = Hello
    }

    implicit val sayHelloKey = TypeKey[SayHello](4)

    implicit val sayHelloWriter = writer[SayHello] { value =>
        Untracked(sayHelloKey, akka.util.ByteString(value.to))
    }

    implicit val sayHelloReader = reader[SayHello] { frame =>
        SayHello(new String(frame.content.toArray))
    }

    case class SayBye(to: String) extends Func {
        override type Error = self.Error
        override type Result = Bye
    }

    implicit val sayByeKey = TypeKey[SayBye](5)

    implicit val sayByeWriter = writer[SayBye] { value =>
        Untracked(sayByeKey, akka.util.ByteString(value.to))
    }

    implicit val sayByeReader = reader[SayBye] { frame =>
        SayBye(new String(frame.content.toArray))
    }

}
