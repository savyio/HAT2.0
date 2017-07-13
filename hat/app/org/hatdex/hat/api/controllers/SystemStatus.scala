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

package org.hatdex.hat.api.controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.util.Clock
import org.hatdex.hat.api.json.HatJsonFormats
import org.hatdex.hat.api.models._
import org.hatdex.hat.api.service.{ SystemStatusService, UsersService }
import org.hatdex.hat.authentication.{ HatApiAuthEnvironment, HatApiController, WithRole }
import org.hatdex.hat.resourceManagement._
import org.ocpsoft.prettytime.PrettyTime
import play.api.i18n.MessagesApi
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._
import play.api.{ Configuration, Logger }

class SystemStatus @Inject() (
    val messagesApi: MessagesApi,
    configuration: Configuration,
    silhouette: Silhouette[HatApiAuthEnvironment],
    hatServerProvider: HatServerProvider,
    systemStatusService: SystemStatusService,
    usersService: UsersService,
    clock: Clock,
    hatDatabaseProvider: HatDatabaseProvider) extends HatApiController(silhouette, clock, hatServerProvider, configuration) with HatJsonFormats {

  val logger = Logger(this.getClass)

  def update(): Action[AnyContent] = UserAwareAction.async { implicit request =>
    hatDatabaseProvider.update(request.dynamicEnvironment.db) map {
      case _ =>
        Ok(Json.toJson(SuccessResponse("Database updated")))
    }
  }

  def status(): Action[AnyContent] = SecuredAction(WithRole(Owner(), Platform())).async { implicit request =>
    val dbStorageAllowance = Math.pow(1000, 3).toLong
    val fileStorageAllowance = Math.pow(1000, 3).toLong

    val eventualStatus = for {
      dbsize <- systemStatusService.tableSizeTotal
      fileSize <- systemStatusService.fileStorageTotal
      maybePreviousLogin <- usersService.previousLogin(request.identity)
    } yield {
      val dbsa = systemStatusService.humanReadableByteCount(dbStorageAllowance)
      val dbsu = systemStatusService.humanReadableByteCount(dbsize)
      val fsa = systemStatusService.humanReadableByteCount(fileStorageAllowance)
      val fsu = systemStatusService.humanReadableByteCount(fileSize)

      val login = maybePreviousLogin.map {
        case login =>
          val p = new PrettyTime()
          HatStatus("Previous Login", StatusKind.Text(p.format(login.date.toDate) + login.applicationName.map(n => s" via $n").getOrElse(""), None))
      } getOrElse {
        HatStatus("Previous Login", StatusKind.Text("Never", None))
      }
      login :: List(
        HatStatus("Owner Email", StatusKind.Text(request.dynamicEnvironment.ownerEmail, None)),
        HatStatus("Database Storage", StatusKind.Numeric(dbsa._1, Some(dbsa._2))),
        HatStatus("File Storage", StatusKind.Numeric(fsa._1, Some(fsa._2))),
        HatStatus("Database Storage Used", StatusKind.Numeric(dbsu._1, Some(dbsu._2))),
        HatStatus("File Storage Used", StatusKind.Numeric(fsu._1, Some(fsu._2))),
        HatStatus("Database Storage Used Share", StatusKind.Numeric(Math.round(dbsize.toDouble / dbStorageAllowance * 100.0), Some("%"))),
        HatStatus("File Storage Used Share", StatusKind.Numeric(Math.round(fileSize.toDouble / fileStorageAllowance * 100.0), Some("%"))))
    }

    eventualStatus map { stats =>
      Ok(Json.toJson(stats))
    }
  }

}

