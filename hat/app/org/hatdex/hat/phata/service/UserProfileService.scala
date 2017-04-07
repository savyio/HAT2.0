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

package org.hatdex.hat.phata.service

import javax.inject.Inject

import org.hatdex.hat.api.json.HatJsonUtilities
import org.hatdex.hat.api.models.ApiDataTable
import org.hatdex.hat.api.service.{ BundleService, DataService }
import org.hatdex.hat.dal.SlickPostgresDriver.backend.Database
import org.hatdex.hat.phata.models.ProfileField
import org.hatdex.hat.resourceManagement.HatServer
import org.hatdex.hat.utils.FutureTransformations
import org.joda.time.LocalDateTime
import play.api.{ Configuration, Logger }
import play.api.libs.json.{ JsError, JsObject, JsSuccess }

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UserProfileService @Inject() (bundleService: BundleService, dataService: DataService, configuration: Configuration) extends HatJsonUtilities {
  private val logger = Logger(this.getClass)
  private implicit def hatServer2db(implicit hatServer: HatServer): Database = hatServer.db

  def getPublicProfile()(implicit server: HatServer): Future[(Boolean, Map[String, String])] = {
    val eventualMaybeProfileTable = bundleService.sourceDatasetTables(Seq(("bheard", "profilet4")), None).map(_.headOption)
    //    val eventualMaybeFacebookTable = bundleService.sourceDatasetTables(Seq(("facebook", "profile_picture")), None).map(_.headOption)
    val eventualProfileRecord = eventualMaybeProfileTable flatMap { maybeTable =>
      FutureTransformations.transform(maybeTable map getProfileTable)
    }

    //    val eventualProfilePicture = eventualMaybeFacebookTable flatMap { maybeTable =>
    //      FutureTransformations.transform(maybeTable map getProfileTable)
    //    }

    //    val eventualProfilePictureField = eventualProfilePicture map { maybeValueTable =>
    //      maybeValueTable map { valueTable =>
    //        val flattenedValues = flattenTableValues(valueTable)
    //        ProfileField("fb_profile_picture", Map("url" -> (flattenedValues \ "url").asOpt[String].getOrElse("")), fieldPublic = true)
    //      }
    //    }

    val profile = for {
      //      profilePictureField <- eventualProfilePictureField
      valueTable <- eventualProfileRecord.map(_.get)
    } yield {
      val flattenedValues = flattenTableValues(valueTable)

      // Profile is public by default
      val publicProfile = !(flattenedValues \ "profilePrivate").asOpt[String].contains("true")
      val profileFields: Map[String, String] = flattenedValues.validate[Map[String, String]] match {
        case s: JsSuccess[Map[String, String]] => s.get
        case e: JsError                        => Map()
      }

      (publicProfile, profileFields)
    }

    profile
  }

  private def getProfileTable(table: ApiDataTable)(implicit server: HatServer) = {
    val fieldset = dataService.getStructureFields(table)

    val startTime = LocalDateTime.now().minusDays(365)
    val endTime = LocalDateTime.now()
    val eventualValues = dataService.fieldsetValues(fieldset, startTime, endTime, Some(1))

    eventualValues.map(values => dataService.restructureTableValuesToRecords(values, Seq(table)))
      .map { records => records.headOption }
      .map(_.flatMap(_.tables.flatMap(_.headOption)))
  }

  private def formatProfile(profileFields: Seq[ProfileField])(implicit server: HatServer): Map[String, Map[String, String]] = {
    val links = Map(profileFields collect {
      // links
      case ProfileField("facebook", values, true) => "Facebook" -> values.getOrElse("link", "")
      case ProfileField("website", values, true)  => "Web" -> values.getOrElse("link", "")
      case ProfileField("youtube", values, true)  => "Youtube" -> values.getOrElse("link", "")
      case ProfileField("linkedin", values, true) => "LinkedIn" -> values.getOrElse("link", "")
      case ProfileField("google", values, true)   => "Google-Plus" -> values.getOrElse("link", "")
      case ProfileField("blog", values, true)     => "Blog" -> values.getOrElse("link", "")
      case ProfileField("twitter", values, true)  => "Twitter" -> values.getOrElse("link", "")
    }: _*).filterNot(_._2 == "").map {
      case (k, v) =>
        k -> (if (v.startsWith("http")) {
          v
        }
        else {
          s"http://$v"
        })
    }

    val contact = Map(profileFields collect {
      // contact
      case ProfileField("primary_email", values, true)     => "primary_email" -> values.getOrElse("value", "")
      case ProfileField("alternative_email", values, true) => "alternative_email" -> values.getOrElse("value", "")
      case ProfileField("mobile", values, true)            => "mobile" -> values.getOrElse("no", "")
      case ProfileField("home_phone", values, true)        => "home_phone" -> values.getOrElse("no", "")
    }: _*).filterNot(_._2 == "")

    val personal = Map(profileFields collect {
      case ProfileField("fb_profile_picture", values, true) => "profile_picture" -> values.getOrElse("url", "")
      // address
      case ProfileField("address_global", values, true) => "address_global" -> {
        values.getOrElse("city", "") + " " +
          values.getOrElse("county", "") + " " +
          values.getOrElse("country", "")
      }
      case ProfileField("address_details", values, true) => "address_details" -> {
        values.getOrElse("address_details", "")
      }

      case ProfileField("personal", values, true) =>
        "personal" -> {
          val title = values.get("title").map(_ + " ").getOrElse("")
          val preferredName = values.get("preferred_name").map(_ + " ").getOrElse("")
          val firstName = values.get("first_name").map { n =>
            if (preferredName.nonEmpty && !preferredName.startsWith(n)) {
              s"($n) "
            }
            else if (preferredName.isEmpty) {
              s"$n "
            }
            else {
              ""
            }
          }.getOrElse("")
          val middleName = values.get("middle_name").map(_ + " ").getOrElse("")
          val lastName = values.getOrElse("last_name", "")
          s"$title$preferredName$firstName$middleName$lastName"
        }

      case ProfileField("emergency_contact", values, true) =>
        "emergency_contact" -> {
          values.getOrElse("first_name", "") + " " +
            values.getOrElse("last_name", "") + " " +
            values.getOrElse("relationship", "") + " " +
            ": " + values.getOrElse("mobile", "") + " "
        }
      case ProfileField("gender", values, true) => "gender" -> values.getOrElse("type", "")

      case ProfileField("nick", values, true)   => "nick" -> values.getOrElse("type", "")
      case ProfileField("age", values, true)    => "age" -> values.getOrElse("group", "")
      case ProfileField("birth", values, true)  => "brithDate" -> values.getOrElse("date", "")
    }: _*).filterNot(_._2 == "")

    val about = Map[String, String](
      "title" -> profileFields.find(_.name == "about").map(_.values.getOrElse("title", "")).getOrElse(""),
      "body" -> profileFields.find(_.name == "about").map(_.values.getOrElse("body", "")).getOrElse(""))

    //    val profile = hatParameters ++ profileParameters.filterNot(_._2 == "")

    val profile = Map(
      "links" -> links,
      "contact" -> contact,
      "profile" -> personal,
      "about" -> about).filterNot(_._2.isEmpty)

    profile
  }
}
