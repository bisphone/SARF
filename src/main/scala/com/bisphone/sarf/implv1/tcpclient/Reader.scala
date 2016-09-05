package com.bisphone.sarf.implv1.tcpclient

import akka.actor._
import akka.stream.actor.{ActorSubscriber, MaxInFlightRequestStrategy}
import akka.util.ByteString

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class Reader(director: ActorRef, demands: Int) extends ActorSubscriber {

  import akka.stream.actor.ActorSubscriberMessage._

  override def preStart(): Unit = {
    context watch director
    request(demands)
  }

  override val requestStrategy = new MaxInFlightRequestStrategy(max = demands) {
    override def inFlightInternally: Int = demands
  }

  override def receive: Receive = {
    case  OnNext(bytes: ByteString) => director ! Director.Event.Received(bytes)
    case OnComplete => context stop self
    case OnError(cause) => director ! Director.Event.StreamHasFailed(cause)
    case Terminated(ref/*director*/) => context stop self
  }

}

object Reader {
  def props(director: ActorRef, demands: Int = 100) = Props { new Reader(director, demands) }
}
