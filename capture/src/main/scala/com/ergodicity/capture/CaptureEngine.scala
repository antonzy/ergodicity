package com.ergodicity.capture

import org.slf4j.LoggerFactory
import akka.actor._
import akka.util.duration._
import com.twitter.ostrich.admin.{RuntimeEnvironment, Service}
import plaza2.{Connection => P2Connection}
import com.typesafe.config.ConfigFactory

object CaptureEngine {
  val log = LoggerFactory.getLogger(getClass.getName)

  var marketCapture: CaptureEngine = null
  var runtime: RuntimeEnvironment = null

  def main(args: Array[String]) {
    try {
      runtime = RuntimeEnvironment(this, args)
      marketCapture = runtime.loadRuntimeConfig[CaptureEngine]()
      marketCapture.start()
    } catch {
      case e =>
        log.error("Exception during startup; exiting!", e)
        System.exit(1)
    }
  }
}

class CaptureEngine(connectionProperties: ConnectionProperties, scheme: Plaza2Scheme, database: CaptureDatabase, kestrel: KestrelConfig) extends Service {
  val log = LoggerFactory.getLogger(classOf[CaptureEngine])

  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)

  implicit val system = ActorSystem("CaptureEngine", ConfigWithDetailedLogging)

  var marketCapture: ActorRef = _

  def start() {
    log.info("Start CaptureEngine")

    val connection = P2Connection()
    val repo = new MarketCaptureRepository(database) with RevisionTracker with SessionTracker with FutSessionContentsTracker with OptSessionContentsTracker
    marketCapture = system.actorOf(Props(new MarketCapture(connection, scheme, repo, kestrel)), "MarketCapture")

    val watcher = system.actorOf(Props(new Actor {
      context.watch(marketCapture)

      protected def receive = {
        case Terminated(ref) if (ref == marketCapture) => shutdown();
      }
    }), "Watcher")

    marketCapture ! Connect(connectionProperties)

    system.scheduler.schedule(120.seconds, 10.seconds) {
      watcher ! Terminated(marketCapture)
    }
  }

  def shutdown() {
    log.info("Shutdown CaptureEngine")
    system.shutdown()
    Thread.sleep(1000)
    System.exit(1)
  }
}