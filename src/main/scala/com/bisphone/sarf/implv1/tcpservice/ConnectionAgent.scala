package com.bisphone.sarf.implv1.tcpservice

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.bisphone.sarf.IOCommand
import org.slf4j.Logger

import scala.collection.mutable

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
private[implv1] class ConnectionAgent(logger: Logger) extends ActorPublisher[IOCommand] {

  val queue = mutable.Queue.empty[IOCommand]

  def tryDeliver(): Unit = {
    if (totalDemand > 0 && queue.nonEmpty) onNext(queue dequeue)
  }

  private def tryPush(cmd: IOCommand): Unit = {
    queue enqueue cmd
    tryDeliver()
  }

  def receive: Receive = {
    case cmd:IOCommand => tryPush(cmd)
    case Request(_) => tryDeliver()
    case Cancel =>
      if (logger.isDebugEnabled()) logger.debug(s"Actor($self) received 'Cancel' signal!")
      context stop self
  }

  override def preStart(): Unit = {
    if (logger.isDebugEnabled()) logger.debug(s"Actor($self) preStart")
  }

  override def postStop(): Unit = {
    if (logger.isDebugEnabled()) logger.debug(s"Actor($self) postStop")
  }

}

private[implv1] object ConnectionAgent {
  def props(logger: Logger) = Props { new ConnectionAgent(logger) }
}
