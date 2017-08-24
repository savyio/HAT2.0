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

package org.hatdex.hat.resourceManagement

import javax.inject.{ Inject, Singleton }

import org.hatdex.hat.dal.SchemaMigration
import org.hatdex.libs.dal.SlickPostgresDriver.api.Database
import play.api.cache.CacheApi
import play.api.libs.ws.WSClient
import play.api.{ Configuration, Logger }
import slick.util.AsyncExecutor

import scala.concurrent.{ ExecutionContext, Future }

trait HatDatabaseProvider {
  val schemaMigration: SchemaMigration

  def database(hat: String)(implicit ec: ExecutionContext): Future[Database]

  def shutdown(db: Database)(implicit ec: ExecutionContext): Future[Unit] = {
    db.shutdown
  }

  def update(db: Database)(implicit ec: ExecutionContext) = {
    schemaMigration.run()(db)
  }
}

@Singleton
class HatDatabaseProviderConfig @Inject() (configuration: Configuration, val schemaMigration: SchemaMigration) extends HatDatabaseProvider {
  def database(hat: String)(implicit ec: ExecutionContext): Future[Database] = {
    Future {
      Database.forConfig(s"hat.${hat.replace(':', '.')}.database")
    } recoverWith {
      case e =>
        Future.failed(new HatServerDiscoveryException(s"Database configuration for $hat incorrect or unavailable", e))
    }
  }
}

@Singleton
class HatDatabaseProviderMilliner @Inject() (
    val configuration: Configuration,
    val cache: CacheApi,
    val ws: WSClient,
    val schemaMigration: SchemaMigration) extends HatDatabaseProvider with MillinerHatSignup {
  val logger = Logger(this.getClass)

  val slickAsyncExecutor = AsyncExecutor(s"slick", 100, 2000)

  def database(hat: String)(implicit ec: ExecutionContext): Future[Database] = {
    getHatSignup(hat) map { signup =>
      val databaseUrl = s"jdbc:postgresql://${signup.databaseServer.get.host}:${signup.databaseServer.get.port}/${signup.database.get.name}"
      //      val executor = AsyncExecutor(hat, numThreads = 3, queueSize = 1000)
      Database.forURL(databaseUrl, signup.database.get.name, signup.database.get.password, driver = "org.postgresql.Driver" /*, executor = slickAsyncExecutor*/ )
    }
  }
}

