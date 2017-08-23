/*
 * Copyright (C) 2017 HAT Data Exchange Ltd
 * SPDX-License-Identifier: AGPL-3.0
 *
 * This file is part of the Hub of All Things project (HAT).
 *
 * HAT is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of
 * the License.
 *
 * HAT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General
 * Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>
 * 2 / 2017
 */

package org.hatdex.hat.resourceManagement.actors

import javax.inject.Inject

import akka.actor._
import com.google.inject.assistedinject.Assisted
import org.hatdex.hat.resourceManagement._
import play.api.Configuration

import scala.concurrent.Future
import scala.concurrent.duration._
import net.ceedubs.ficus.Ficus._
import play.api.cache.CacheApi

object HatServerActor {
  sealed trait HatServerActorMessage
  case class HatConnect() extends HatServerActorMessage
  case class HatConnected(hatServer: HatServer) extends HatServerActorMessage
  case class HatFailed(error: HatServerDiscoveryException) extends HatServerActorMessage
  case class HatRetrieve() extends HatServerActorMessage
  case class HatState(hatServer: HatServer) extends HatServerActorMessage

  trait Factory {
    def apply(hat: String): Actor
  }
}

class HatServerActor @Inject() (
    @Assisted hat: String,
    configuration: Configuration,
    hatDatabaseProvider: HatDatabaseProvider,
    hatKeyProvider: HatKeyProvider,
    cacheApi: CacheApi) extends Actor with ActorLogging with Stash {
  import HatServerActor._
  import org.hatdex.hat.api.service.IoExecutionContext.ioThreadPool
  val idleTimeout = configuration.underlying.as[FiniteDuration]("resourceManagement.serverIdleTimeout")

  def receive: Receive = {
    case message: HatRetrieve =>
      log.debug(s"RECEIVE HATRetrieve, stashing, connecting")
      stash()
      context.become(connecting)
      context.setReceiveTimeout(idleTimeout)
      connect()

    case message =>
      log.debug(s"RECEIVE Received unknown message: $message")
  }

  def connecting: Actor.Receive = {
    case HatConnected(server) =>
      unstashAll()
      context.become(connected(server))
      context.parent ! HatServerProviderActor.HatServerStarted(hat)
    case HatFailed(error) =>
      unstashAll()
      context.setReceiveTimeout(idleTimeout * 10)
      context.become(failed(error))
    case message: HatServerActorMessage =>
      stash()
  }

  def connected(server: HatServer): Actor.Receive = {
    case message: HatRetrieve =>
      log.debug(s"HAT $hat connected, replying")
      sender ! server
    case ReceiveTimeout =>
      log.info(s"HAT $hat idle, shutting down")
      shutdown(server)
      context.become(receive)
      context.parent ! HatServerProviderActor.HatServerStopped(hat)
    case message =>
      log.warning(s"CONNECTED Received unknown message: $message")
  }

  def failed(e: HatServerDiscoveryException): Actor.Receive = {
    case message: HatServerActorMessage =>
      log.debug(s"HAT $hat failed (${e.getMessage}), replying")
      sender ! e
    case ReceiveTimeout =>
      log.debug(s"HAT failed timeout, becoming neutral")
      context.become(receive)
    case message =>
      log.warning(s"FAILED Received unknown message: $message")
  }

  private def shutdown(server: HatServer): Future[Unit] = {
    cacheApi.remove(s"hatServer:${server.domain}")
    hatDatabaseProvider.shutdown(server.db)
  }

  private def connect() = {
    server(hat).map { hatConnected =>
      self ! HatConnected(hatConnected)
    } recover {
      case e: HatServerDiscoveryException =>
        self ! HatFailed(e)
    }
  }

  private def server(hat: String): Future[HatServer] = {
    val server = for {
      privateKey <- hatKeyProvider.privateKey(hat)
      publicKey <- hatKeyProvider.publicKey(hat)
      db <- hatDatabaseProvider.database(hat)
      ownerEmail <- hatKeyProvider.ownerEmail(hat)
    } yield {
      val hatServer = HatServer(hat, hat.split('.').headOption.getOrElse(hat), ownerEmail, privateKey, publicKey, db)
      log.debug(s"HAT connection info $hatServer")
      hatServer
    }

    server onFailure {
      case e =>
        log.warning(s"Error while trying to fetch HAT $hat Server configuration: ${e.getMessage}")
    }

    server
  }
}