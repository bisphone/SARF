import java.io.{InputStream, OutputStream}
import java.net.Socket

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.ByteString
import com.bisphone.sarf.implv1.{TCPClient, TCPServer}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForServer}
import com.bisphone.sarf.{Constant, IOCommand}
import com.bisphone.util.ByteOrder
import com.typesafe.config.ConfigFactory
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class SimpleServerSuite extends TestKit(ActorSystem()) with FlatSpecLike with Matchers with BeforeAndAfter {

  implicit val ec = ExecutionContext.Implicits.global

  val tcp = TCPConfigForServer("localhost", 2424, 100)

  val stream = StreamConfig(2000, ByteOrder.BigEndian, 10)

  val tmp = TCPServer(
    "echochat",
    tcp, stream, true
  ) { bytes =>
    implicit val order = stream.byteOrder.javaValue
    val hisMessage = new String(bytes.toArray)
    val reply = s">> ${hisMessage}"
    val response = ByteString(reply)
    Future successful IOCommand.Send(response)
  }

  try Await.result(tmp, 1 minute) catch {
    case NonFatal(cause) => info(s"Error: ${cause}")
      cause.printStackTrace()
      fail("Can't prepare resources")
  }

  info("Going to run tests ...")

  def write(output: OutputStream, str: String): Unit = {
    implicit val byteOrder = stream.byteOrder.javaValue
    val builder = ByteString.newBuilder
    val tmp = ByteString(str)
    val bytes = builder.putInt(tmp.size).append(tmp).result().toArray
    output.write(bytes)
  }

  def read(input: InputStream): String = {
    implicit val byteOrder = stream.byteOrder.javaValue
    val buf = Array.ofDim[Byte](Constant.lenOfLenField + stream.maxSliceSize)
    input.read(buf)
    val mirror = buf.clone
    val size = stream.byteOrder.decodeInt(buf.iterator, Constant.lenOfLenField)
    if (size > stream.maxSliceSize) throw new RuntimeException("Input is bigger than max-slice-size")
    new String(mirror.drop(Constant.lenOfLenField).take(size))
  }

  "SimpleServerSuite" should "make a call with a simple blocking client!" in {
    val socket = new Socket("localhost", 2424)
    write(socket.getOutputStream, "Hello")
    val response = read(socket.getInputStream)
    info(response)
    response.size should be === ">> Hello".size
    response should be === ">> Hello"
  }


}
