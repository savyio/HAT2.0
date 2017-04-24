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

package org.hatdex.hat.api.service

import java.util.UUID

import org.hatdex.hat.authentication.models.{ HatAccessLog, HatUser }
import org.hatdex.hat.dal.SlickPostgresDriver.api._
import org.hatdex.hat.dal.Tables._
import org.hatdex.hat.dal.ModelTranslation
import org.joda.time.LocalDateTime
import play.api.Logger

import scala.concurrent.{ ExecutionContext, Future }

class UsersService extends DalExecutionContext {
  val logger = Logger(this.getClass)

  def listUsers()(implicit db: Database): Future[Seq[HatUser]] = {
    db.run {
      UserUser.result
    } map { users =>
      users.map(ModelTranslation.fromDbModel)
    }
  }

  def getUser(userId: UUID)(implicit db: Database): Future[Option[HatUser]] = {
    db.run {
      UserUser.filter(_.userId === userId).result
    } map { users =>
      users.map(ModelTranslation.fromDbModel)
        .headOption
    }
  }

  def getUser(username: String)(implicit db: Database): Future[Option[HatUser]] = {
    logger.info(s"Get user by username: ${username}")
    db.run {
      UserUser.filter(_.email === username).result
    } map { users =>
      users.map(ModelTranslation.fromDbModel)
        .headOption
    }
  }

  def getUserForCredentials(username: String, passwordHash: String)(implicit db: Database): Future[Option[HatUser]] = {
    db.run {
      UserUser.filter(_.email === username).result
    } map { users =>
      users.map(ModelTranslation.fromDbModel)
        .headOption
    }
  }

  def saveUser(user: HatUser)(implicit db: Database): Future[HatUser] = {
    val userRow = UserUserRow(user.userId, LocalDateTime.now(), LocalDateTime.now(),
      user.email, user.pass, // The password is assumed to come in hashed, hence stored as is!
      user.name, user.role, enabled = user.enabled)

    db.run {
      for {
        _ <- (UserUser returning UserUser).insertOrUpdate(userRow)
        updated <- UserUser.filter(_.userId === user.userId).result
      } yield updated
    } map { user =>
      ModelTranslation.fromDbModel(user.head)
    }
  }

  def deleteUser(userId: UUID)(implicit db: Database): Future[Unit] = {
    val deleteUserQuery = UserUser.filter(_.userId === userId)
      .filterNot(_.role === "owner")
      .filterNot(_.role === "platform")

    db.run(deleteUserQuery.delete).map(_ => ())
  }

  def changeUserState(userId: UUID, enabled: Boolean)(implicit db: Database): Future[Unit] = {
    val query = UserUser.filter(_.userId === userId)
      .map(u => (u.enabled))
      .update((enabled))
    db.run(query).map(_ => ())
  }

  def removeUser(username: String)(implicit db: Database): Future[Unit] = {
    db.run {
      UserUser.filter(_.email === username).delete
    } map { _ => () }
  }

  def previousLogin(user: HatUser)(implicit db: Database): Future[Option[HatAccessLog]] = {
    val query = for {
      access <- UserAccessLog.filter(l => l.userId === user.userId).sortBy(_.date.desc).take(2).drop(1)
      user <- access.userUserFk
    } yield (access, user)
    db.run(query.result)
      .map(_.headOption)
      .map(_.map(au => ModelTranslation.fromDbModel(au._1, ModelTranslation.fromDbModel(au._2))))
  }

  def logLogin(user: HatUser, loginType: String, scope: String, appName: Option[String], appResource: Option[String])(implicit db: Database): Future[Unit] = {
    val accessLog = UserAccessLogRow(LocalDateTime.now(), user.userId, loginType, scope, appName, appResource)
    db.run(UserAccessLog += accessLog).map(_ => ())
  }

}
