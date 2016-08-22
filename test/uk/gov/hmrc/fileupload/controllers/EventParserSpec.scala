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

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.mvc.Result
import play.api.test.FakeRequest
import uk.gov.hmrc.fileupload.events._
import uk.gov.hmrc.fileupload.{EnvelopeId, FileId}
import uk.gov.hmrc.play.test.UnitSpec

class EventParserSpec extends UnitSpec with ScalaFutures {
  implicit override val patienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(5, Millis))

  "event parser" should {

    "should parse a quarantined event" in {
      val request = FakeRequest("POST", "/file-upload/events/quarantined")
      val body = """{"envelopeId":"env1","fileId":"file1"}""".getBytes

      val parserIteratee: Iteratee[Array[Byte], Either[Result, Event]] = EventParser(request)
      val futureValue: Either[Result, Event] = Enumerator(body).run(parserIteratee)

      futureValue shouldBe Right(Quarantined(EnvelopeId("env1"), FileId("file1")))
    }

    "should parse a to transient moved event" in {
      val request = FakeRequest("POST", "/file-upload/events/ToTransientMoved")
      val body = """{"envelopeId":"env1","fileId":"file1"}""".getBytes

      val parserIteratee: Iteratee[Array[Byte], Either[Result, Event]] = EventParser(request)
      val futureValue: Either[Result, Event] = Enumerator(body).run(parserIteratee)

      futureValue shouldBe Right(ToTransientMoved(EnvelopeId("env1"), FileId("file1")))
    }

    "should parse a moving to transient failed event" in {
      val request = FakeRequest("POST", "/file-upload/events/MovingToTransientFailed")
      val body = """{"envelopeId":"env1","fileId":"file1", "reason": "something not good"}""".getBytes

      val parserIteratee: Iteratee[Array[Byte], Either[Result, Event]] = EventParser(request)
      val futureValue: Either[Result, Event] = Enumerator(body).run(parserIteratee)

      futureValue shouldBe Right(MovingToTransientFailed(EnvelopeId("env1"), FileId("file1"), "something not good"))
    }

    "should parse a no virus detected event" in {
      val request = FakeRequest("POST", "/file-upload/events/novirusdetected")
      val body = """{"envelopeId":"env1","fileId":"file1"}""".getBytes

      val parserIteratee: Iteratee[Array[Byte], Either[Result, Event]] = EventParser(request)
      val futureValue: Either[Result, Event] = Enumerator(body).run(parserIteratee)

      futureValue shouldBe Right(NoVirusDetected(EnvelopeId("env1"), FileId("file1")))
    }

    "should parse a virus detected event" in {
      val request = FakeRequest("POST", "/file-upload/events/virusdetected")
      val body = """{"envelopeId":"env1","fileId":"file1", "reason": "something not good"}""".getBytes

      val parserIteratee: Iteratee[Array[Byte], Either[Result, Event]] = EventParser(request)
      val futureValue: Either[Result, Event] = Enumerator(body).run(parserIteratee)

      futureValue shouldBe Right(VirusDetected(EnvelopeId("env1"), FileId("file1"), "something not good"))
    }

    "should respond with a Failure when an unexpected event type is given" in {
      val request = FakeRequest("POST", "/file-upload/events/unexpectedevent")
      val body = """{"envelopeId":"env1","fileId":"file1"}""".getBytes

      val parserIteratee: Iteratee[Array[Byte], Either[Result, Event]] = EventParser(request)
      val futureValue: Either[Result, Event] = Enumerator(body).run(parserIteratee)

      val isLeftResult = futureValue match {
        case Left(result) => true
        case _ => false
      }

      isLeftResult shouldBe true
    }
  }

}