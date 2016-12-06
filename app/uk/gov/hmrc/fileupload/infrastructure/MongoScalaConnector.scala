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

package uk.gov.hmrc.fileupload.infrastructure

import org.mongodb.scala.{MongoClient, MongoDatabase}
import play.api.Mode.Mode
import play.api.{Logger, Mode}

// TODO create test
class MongoScalaConnector(config: play.api.Configuration, mode: Mode)  {

  def mongoDatabase: MongoDatabase = {

    val mongoConfig = config.getConfig("mongodb")
      .getOrElse(config.getConfig(s"${mode}.mongodb")
      .getOrElse(config.getConfig(s"${Mode.Dev}.mongodb")
      .getOrElse(throw new Exception("The application does not contain required mongodb configuration"))))

    mongoConfig.getString("uri") match {
      case Some(uri) => {

        mongoConfig.getInt("channels").foreach { _ =>
          Logger.warn("the mongodb.channels configuration key has been removed and is now ignored. Please use the mongodb URL option described here: https://docs.mongodb.org/manual/reference/connection-string/#connections-connection-options. https://github.com/ReactiveMongo/ReactiveMongo/blob/0.11.3/driver/src/main/scala/api/api.scala#L577")
        }

        val (host, dbName) = uri.splitAt(uri.lastIndexOf('/'))

        MongoClient(host).getDatabase(dbName.replace("/", ""))
      }
      case _ => throw new Exception("ReactiveMongoPlugin error: no MongoConnector available?")
    }
  }

//        TODO implement failover strategy for scala driver
//        val failoverStrategy: Option[FailoverStrategy] = mongoConfig.getConfig("failoverStrategy") match {
//          case Some(fs: Configuration) => {
//
//            val initialDelay: FiniteDuration = fs.getLong("initialDelayMsecs").map(delay => new FiniteDuration(delay, TimeUnit.MILLISECONDS)).getOrElse(FailoverStrategy().initialDelay)
//            val retries: Int = fs.getInt("retries").getOrElse(FailoverStrategy().retries)
//
//            Some(FailoverStrategy().copy(initialDelay = initialDelay, retries = retries, delayFactor = DelayFactor(fs.getConfig("delay"))))
//          }
//          case _ => None
//        }
//        new MongoConnector(uri, failoverStrategy)

}
