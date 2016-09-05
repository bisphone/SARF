package com.bisphone.sarf.implv1

import com.bisphone.sarf

import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, ActorSystem, Props}
import org.slf4j.Logger

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */

class StatCollector (
  name: String,
  config: StatCollector.Config,
  logger: Logger
) extends Actor {

  import StatCollector._

  val funcbuf = mutable.HashMap.empty[String, FunctionSummary]
  val connbuf = mutable.HashMap.empty[String, ConnectionSummary]
  var zero = System.currentTimeMillis()

  def receive: Receive = {

    case Done(tag, duration) => funcbuf.get(tag) match {
      case Some(obj) => obj.done(duration)
      case None => funcbuf(tag) = FunctionSummary(tag, 1, duration, 0, 0)
    }

    case Failed(tag, duration) => funcbuf.get(tag) match {
      case Some(obj) => obj.failure(duration)
      case None => funcbuf(tag) = FunctionSummary(tag, 0, 0, 1, duration)
    }

    case In(tag, bytes) => connbuf.get(tag) match {
      case Some(obj) => obj.in(bytes)
      case None => connbuf(tag) = ConnectionSummary(tag, System.currentTimeMillis(), bytes, 0, 1)
    }

    case Out(tag, bytes) => connbuf.get(tag) match {
      case Some(obj) => obj.out(bytes)
      case None => /* Ignore It */
    }

    case GenReport => report()
  }

  override def preStart(): Unit = {
    zero = System.currentTimeMillis()
    context.system.scheduler.schedule(
      config.every,
      config.every,
      self,
      GenReport
    )(context.dispatcher)
  }

  override def postStop(): Unit = {
    report()
  }

  def report(): Unit = {

    val now = System.currentTimeMillis()

    var totalDones = 0l
    var totalFailures = 0l
    var totalLatency = 0l

    funcbuf.values.foreach { item =>
      totalDones += item.doneCount
      totalFailures += item.failureCount
      totalLatency += item.doneDuration + item.failureDuration
    }

    val totalCalls = totalDones + totalFailures
    val avgLatency = totalCalls.asInstanceOf[Double] / totalLatency
    val donePercent: Double = totalDones * 100 / totalCalls

    val mostCalled = funcbuf.values.toList.sortBy{_.calls}.take(config.justMostCalled)

    funcbuf.clear()


    var totalIn = 0l
    var totalOut = 0l
    var totalConns = connbuf.size

    connbuf.values.foreach { item =>
      totalIn += item.inCount
      totalOut += item.outCount
    }

    val str = s"""{
       |'subject': 'Report(${name})',
       |'from': ${zero},
       |'until': ${now},
       |'duration': ${now - zero}
       |'totalCalls': ${totalDones},
       |'donePercent': ${donePercent},
       |'avgLatency': ${avgLatency},
       |'bytes.in': ${totalIn},
       |'bytes.out': ${totalOut},
       |'connections': ${totalConns},
       |'mostCalled': ["${mostCalled.map(_.tag).mkString("\",\"")}"]
       |}""".stripMargin

    zero = now

    log(str)

  }

  def log(str: String): Unit = logger info str
}

object StatCollector {

  def props(
    name: String,
    config: Config,
    logger: Logger
  ) = Props { new StatCollector(name, config, logger) }


  def apply(name: String, config: Config, logger: Logger)(implicit ctx: ActorRefFactory) = {
    val ref = ctx.actorOf(props(name, config, logger), name)
    new Ref(ref)
  }

  case class Config(
    every: FiniteDuration,
    justMostCalled: Int
  )

  private[StatCollector] trait Conclusion
  private[StatCollector] case class Done(tag: String, duration: Long) extends Conclusion
  private[StatCollector] case class Failed(tag: String, duration: Long) extends Conclusion

  private[StatCollector] trait IOConclusion
  private[StatCollector] case class In(client: String, bytes: Long) extends IOConclusion
  private[StatCollector] case class Out(client: String, bytes: Long) extends IOConclusion

  private[StatCollector] case class FunctionSummary(
    val tag: String,
    var doneCount: Int,
    var doneDuration: Long,
    var failureCount: Int,
    var failureDuration: Long
  ) {
    def done(duration: Long) = {
      doneCount += 1
      doneDuration += duration
    }

    def failure(duration: Long) = {
      failureCount += 1
      failureDuration += duration
    }

    def calls() = doneCount + failureCount
  }

  private[StatCollector] case class ConnectionSummary(
    val tag: String,
    val from: Long,
    var inCount: Long,
    var outCount: Long,
    var request: Long
  ) {

    def in(bytes: Long) = {
      inCount += bytes
      request += 1
    }

    def out(bytes: Long) = {
      outCount += bytes
    }

  }

  private[StatCollector] case object GenReport

  class Ref(val ref: ActorRef) extends sarf.StatCollector {

    def done[T] (tag: sarf.StatTag[T], duration: Long): Unit = ref ! Done(tag.tag, duration)

    def failed[T](tag: sarf.StatTag[T], duration: Long): Unit = ref ! Failed(tag.tag, duration)

    def read[T] (tag: sarf.StatTag[T], bytes: Long): Unit = ref ! In(tag.tag, bytes)

    def wrote[T] (tag: sarf.StatTag[T], bytes: Long): Unit = ref ! Out(tag.tag, bytes)

  }

}

/*
 * What about Connection Stats ?!
 */