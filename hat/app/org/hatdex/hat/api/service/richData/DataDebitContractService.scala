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
 * 5 / 2017
 */

package org.hatdex.hat.api.service.richData

import java.util.UUID

import org.hatdex.hat.api.models._
import org.hatdex.hat.api.service.DalExecutionContext
import org.hatdex.hat.dal.ModelTranslation
import org.hatdex.hat.dal.SlickPostgresDriver.api._
import org.hatdex.hat.dal.Tables._
import org.joda.time.LocalDateTime
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.Future

class DataDebitContractService extends DalExecutionContext with RichDataJsonFormats {

  val logger = Logger(this.getClass)

  def createDataDebit(key: String, ddRequest: DataDebitRequest, userId: UUID)(implicit db: Database): Future[RichDataDebit] = {
    val query = for {
      dd <- (DataDebitContract returning DataDebitContract) += DataDebitContractRow(key, LocalDateTime.now(), userId)
      bundle <- (DataBundles returning DataBundles) += DataBundlesRow(ddRequest.bundle.name, Json.toJson(ddRequest.bundle.bundle))
      ddb <- (DataDebitBundle returning DataDebitBundle) += DataDebitBundleRow(dd.dataDebitKey, bundle.bundleId, LocalDateTime.now(), ddRequest.startDate, ddRequest.endDate, ddRequest.rolling, enabled = false)
    } yield ddb

    db.run(query.transactionally)
      .flatMap(_ => dataDebit(key)) // Retrieve the data debit
      .map(_.get) // Data Debit must be Some as it has been inserted
      .recover {
        case e: PSQLException if e.getMessage.contains("duplicate key value violates unique constraint \"data_bundles_pkey\"") =>
          throw RichDataDuplicateBundleException("Data bundle with such ID already exists")
        case e: PSQLException if e.getMessage.contains("duplicate key value violates unique constraint \"data_debit_contract_pkey\"") =>
          throw RichDataDuplicateDebitException("Data Debit with such ID already exists")
        case e =>
          throw e
      }
  }

  def updateDataDebitBundle(key: String, ddRequest: DataDebitRequest, userId: UUID)(implicit db: Database): Future[RichDataDebit] = {
    val query = for {
      dd <- DataDebitContract.filter(dd => dd.dataDebitKey === key && dd.clientId === userId).result.map(_.head)
      bundle <- (DataBundles returning DataBundles) += DataBundlesRow(ddRequest.bundle.name, Json.toJson(ddRequest.bundle.bundle))
      ddb <- (DataDebitBundle returning DataDebitBundle) += DataDebitBundleRow(dd.dataDebitKey, bundle.bundleId, LocalDateTime.now(), LocalDateTime.now(), ddRequest.endDate, ddRequest.rolling, enabled = false)
    } yield ddb

    db.run(query.transactionally)
      .flatMap(_ => dataDebit(key)) // Retrieve the data debit
      .map(_.get) // Data Debit must be Some as it has been inserted
      .recover {
        case e: PSQLException if e.getMessage.contains("duplicate key value violates unique constraint \"data_bundles_pkey\"") =>
          throw RichDataDuplicateBundleException("Data bundle with such ID already exists")
        case e: UnsupportedOperationException if e.getMessage.contains("empty.head") =>
          throw RichDataDebitException("Data Debit being updated does not exist")
        case e =>
          throw e
      }
  }

  def dataDebit(dataDebitKey: String)(implicit db: Database): Future[Option[RichDataDebit]] = {
    val query = for {
      ddb <- DataDebitBundle.filter(_.dataDebitKey === dataDebitKey)
      dd <- ddb.dataDebitContractFk
      bundle <- ddb.dataBundlesFk
      client <- dd.userUserFk
    } yield (dd, client, (ddb, bundle))

    db.run(query.result).map(_.unzip3) map {
      case (dd, client, ddBundle) =>
        (dd.headOption, client.headOption) match {
          case (Some(dateDebit), Some(ddClient)) => Some(ModelTranslation.fromDbModel(dateDebit, ddClient, ddBundle))
          case _                                 => None
        }
    }
  }

  def all()(implicit db: Database): Future[Seq[RichDataDebit]] = {
    val query = for {
      ddb <- DataDebitBundle
      dd <- ddb.dataDebitContractFk
      bundle <- ddb.dataBundlesFk
      client <- dd.userUserFk
    } yield (dd, client, (ddb, bundle))

    db.run(query.result)
      .map { result =>
        result.groupBy(_._1.dataDebitKey)
          .values.toSeq
          .flatMap {
            _.unzip3 match {
              case (dd, client, ddBundle) =>
                (dd.headOption, client.headOption) match {
                  case (Some(dateDebit), Some(ddClient)) => Some(ModelTranslation.fromDbModel(dateDebit, ddClient, ddBundle))
                  case _                                 => None
                }
            }
          }
      }
  }

  def dataDebitDisable(dataDebitKey: String)(implicit db: Database): Future[Unit] = {
    val dataBundlesDisabled = DataDebitBundle.filter(_.dataDebitKey === dataDebitKey)
      .map(_.enabled)
      .update(false)
    db.run(dataBundlesDisabled)
      .map(_ => ())
  }

  def dataDebitEnableBundle(dataDebitKey: String, bundleId: String)(implicit db: Database): Future[Unit] = {
    val dataBundlesDisabled = DataDebitBundle
      .filter(_.dataDebitKey === dataDebitKey)
      .map(_.enabled)
      .update(false)

    val newestBundleEnabled = DataDebitBundle
      .filter(_.dataDebitKey === dataDebitKey)
      .filter(_.bundleId === bundleId)
      .map(_.enabled)
      .update(true)

    db.run(DBIO.seq(dataBundlesDisabled, newestBundleEnabled).transactionally)
      .map(_ => ())
  }

}

