package com.ergodicity.cgate

import akka.actor.{LoggingFSM, ActorRef, Actor, FSM}
import ru.micexrts.cgate.{ErrorCode, MessageType}
import ru.micexrts.cgate.messages._
import scalaz._
import Scalaz._
import java.nio.{ByteOrder, ByteBuffer}
import akka.event.Logging


sealed trait DataStreamState

object DataStreamState {

  case object Closed extends DataStreamState

  case object Opened extends DataStreamState

  case object Online extends DataStreamState

}


sealed trait StreamEvent

object StreamEvent {

  case object Open extends StreamEvent

  case object Close extends StreamEvent

  case object TnBegin extends StreamEvent

  case object TnCommit extends StreamEvent

  case object StreamOnline extends StreamEvent

  case class StreamData(tableIndex: Int, data: ByteBuffer) extends StreamEvent

  case class LifeNumChanged(lifeNum: Long) extends StreamEvent

  case class ClearDeleted(tableIndex: Int, rev: Long) extends StreamEvent

  case class ReplState(state: String) extends StreamEvent

  case class UnsupportedMessage(msg: Message) extends StreamEvent

}

class DataStreamSubscriber(dataStream: ActorRef) extends Subscriber {

  import StreamEvent._

  private def decode(msg: Message) = msg.getType match {
    case MessageType.MSG_OPEN => Open

    case MessageType.MSG_CLOSE => Close

    case MessageType.MSG_TN_BEGIN => TnBegin

    case MessageType.MSG_TN_COMMIT => TnCommit

    case MessageType.MSG_P2REPL_ONLINE => StreamOnline

    case MessageType.MSG_STREAM_DATA =>
      val dataMsg = msg.asInstanceOf[StreamDataMessage]
      StreamData(dataMsg.getMsgIndex, clone(dataMsg.getData))

    case MessageType.MSG_P2REPL_LIFENUM =>
      val lifeNumMsg = msg.asInstanceOf[P2ReplLifeNumMessage]
      LifeNumChanged(lifeNumMsg.getLifenum)

    case MessageType.MSG_P2REPL_CLEARDELETED =>
      val clearMsg = msg.asInstanceOf[P2ReplClearDeletedMessage]
      ClearDeleted(clearMsg.getTableIdx, clearMsg.getTableRev)

    case MessageType.MSG_P2REPL_REPLSTATE =>
      val replStateMsg = msg.asInstanceOf[P2ReplStateMessage]
      ReplState(replStateMsg.getReplState)

    case _ => UnsupportedMessage(msg)
  }

  def handleMessage(msg: Message) = {
    dataStream ! decode(msg)
    ErrorCode.OK
  }
}

object DataStream {

  case class BindTable(tableIndex: Int, ref: ActorRef)

  case class SubscribeReplState(ref: ActorRef)

  case class UnsubscribeReplState(ref: ActorRef)

  case class DataStreamReplState(stream: ActorRef, state: String)

  sealed trait BindingResult

  case class BindingFailed(ds: ActorRef, tableIndex: Int) extends BindingResult

  case class BindingSucceed(ds: ActorRef, tableIndex: Int) extends BindingResult

}

class DataStream extends Actor with FSM[DataStreamState, Map[Int, Seq[ActorRef]]] {

  import DataStream._
  import StreamEvent._
  import DataStreamState._

  var replStateSubscribers: Seq[ActorRef] = Seq()

  startWith(Closed, Map())

  when(Closed) {
    case Event(Open, _) => goto(Opened)

    case Event(BindTable(idx, ref), subscribers) =>
      sender ! BindingSucceed(self, idx)
      stay() using (subscribers <+> Map(idx -> Seq(ref)))
  }

  when(Opened)(handleStreamEvents orElse {
    case Event(StreamOnline, subscribers) => goto(Online)

    case Event(Close, _) => goto(Closed)
  })

  when(Online)(handleStreamEvents orElse {
    case Event(Close, _) => goto(Closed)
  })

  whenUnhandled {
    case Event(e@LifeNumChanged(_), subscribers) =>
      subscribers.values.foreach(_.foreach(_ ! e))
      stay()

    case Event(BindTable(idx, _), _) =>
      sender ! BindingFailed(self, idx)
      stay()

    case Event(SubscribeReplState(ref), _) =>
      replStateSubscribers = ref +: replStateSubscribers
      stay()

    case Event(UnsubscribeReplState(ref), _) =>
      replStateSubscribers = replStateSubscribers filterNot (_ == ref)
      stay()
  }

  private def handleStreamEvents: StateFunction = {
    case Event(e@(TnBegin | TnCommit), subscribers) =>
      subscribers.values.foreach(_.foreach(_ ! e))
      stay()

    case Event(e@StreamData(idx, _), subscribers) =>
      subscribers.get(idx).foreach(_.foreach(_ ! e))
      stay()

    case Event(e@ClearDeleted(idx, _), subscribers) =>
      subscribers.get(idx).foreach(_.foreach(_ ! e))
      stay()

    case Event(e@ReplState(state), _) =>
      replStateSubscribers.foreach(_ ! DataStreamReplState(self, state))
      stay()

  }

  initialize
}