/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.fileupload.controllers

import com.google.common.base.Charsets
import com.google.common.io.BaseEncoding
import uk.gov.hmrc.play.config.ServicesConfig

object AuthBasicModule extends ServicesConfig {

  def check (auth:Option[String]): Boolean = {

    var users: List[User] = List.empty

    val users1 = getString("basicAuth.authorizedUsers").split(";").toList

    for (i <- 0 to users1.length-1) {
      users = users :+ User(users1(i).split(":").toList(0), users1(i).split(":").toList(1))
    }

    auth match {
      case Some(auth) =>
        users.exists(user => "Basic " + BaseEncoding.base64().encode((user.name + ":" + user.password).getBytes(Charsets.UTF_8)) == auth)
      case None => false
    }
  }

}

case class User(name: String, password: String)
