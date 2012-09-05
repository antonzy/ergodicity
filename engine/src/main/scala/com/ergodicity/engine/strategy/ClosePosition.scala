package com.ergodicity.engine.strategy

import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.engine.StrategyEngine
import com.ergodicity.engine.service.Portfolio
import com.ergodicity.core.PositionsTracking.{GetPositions, Positions}
import akka.dispatch.Await
import com.ergodicity.engine.strategy.InstrumentWatchDog.WatchDogConfig
import com.ergodicity.core.position.Position
import com.ergodicity.core.Isin
import akka.util.{Duration, Timeout}

object CloseAllPositions {

  implicit case object CloseAllPositions extends StrategyId

  def apply() = new StrategiesFactory {

    def strategies = (strategy _ :: Nil)

    def strategy(engine: StrategyEngine) = Props(new CloseAllPositions(engine))
  }
}

sealed trait CloseAllPositionsState

object CloseAllPositionsState {

  case object Ready extends CloseAllPositionsState

  case object CatchingInstruments extends CloseAllPositionsState

}

class CloseAllPositions(val engine: StrategyEngine)(implicit id: StrategyId) extends Strategy with Actor with FSM[CloseAllPositionsState, Unit] with InstrumentWatcher {

  import CloseAllPositionsState._

  val portfolio = engine.services.service(Portfolio.Portfolio)

  // Configuration and implicits
  implicit object WatchDog extends WatchDogConfig(self, true, true)

  implicit val timeout = Timeout(1.second)

  implicit val executionContext = context.system

  // Positions that we are going to close
  val positions: Map[Isin, Position] = getOpenedPositions(5.seconds)

  override def preStart() {
    log.info("Started CloseAllPositions")
    log.debug("Going to close positions = " + positions)
    engine.reportReady(positions)
  }

  startWith(Ready, ())

  when(Ready) {
    case Event(_, _) => stay()
  }

  private def getOpenedPositions(atMost: Duration): Map[Isin, Position] = {
    val future = (portfolio ? GetPositions).mapTo[Positions]
    Await.result(future, atMost).positions
  }
}
