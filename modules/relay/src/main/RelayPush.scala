package lila.relay

import akka.actor._
import scala.concurrent.duration._

import lila.study.MultiNotation
import lila.hub.EarlyMultiThrottler

final class RelayPush(sync: RelaySync, api: RelayApi)(implicit
    system: ActorSystem,
    ec: scala.concurrent.ExecutionContext
) {

  private val throttler = system.actorOf(Props(new EarlyMultiThrottler(logger = logger)))

  def apply(relay: Relay, notation: String): Fu[Option[String]] =
    if (relay.sync.hasUpstream)
      fuccess("The relay has an upstream URL, and cannot be pushed to.".some)
    else
      fuccess {
        throttler ! EarlyMultiThrottler.Work(
          id = relay.id.value,
          run = () => pushNow(relay, notation),
          cooldown = if (relay.official) 3.seconds else 7.seconds
        )
        none
      }

  private def pushNow(relay: Relay, notation: String): Funit =
    RelayFetch
      .multiNotationToGames(MultiNotation.split(notation, RelayFetch.maxChapters(relay)))
      .flatMap {
        sync(relay, _)
      }
      .map { res =>
        SyncLog.event(res.moves, none)
      }
      .recover { case e: Exception =>
        SyncLog.event(0, e.some)
      }
      .flatMap { event =>
        api.update(relay)(_.withSync(_ addLog event)).void
      }
}
