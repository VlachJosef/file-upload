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

package uk.gov.hmrc.fileupload.file

import java.util.UUID

import play.api.libs.json.{Format, JsObject, Json}

object FileMetadata{
  implicit val fileMetaDataReads: Format[FileMetadata] = Json.format[FileMetadata]
}

case class FileMetadata(_id: String = UUID.randomUUID().toString,
                        filename: Option[String] = None,
                        contentType: Option[String] = None,
                        revision: Option[Int] = None,
                        metadata: Option[JsObject] = None)
