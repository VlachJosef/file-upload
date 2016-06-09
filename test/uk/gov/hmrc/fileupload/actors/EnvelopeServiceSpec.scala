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

package uk.gov.hmrc.fileupload.actors

import java.lang.Math.abs

import akka.actor.{ActorRef, Inbox, Actor, ActorSystem}
import akka.testkit.{TestActorRef, TestActors}
import akka.util.Timeout
import org.joda.time.DateTime
import org.junit.Assert
import org.junit.Assert.assertTrue
import play.api.libs.json.{Json, JsObject, JsValue}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.fileupload.Support
import uk.gov.hmrc.fileupload.actors.Storage.Save
import uk.gov.hmrc.fileupload.actors.IdGenerator.NextId
import uk.gov.hmrc.fileupload.controllers.BadRequestException
import uk.gov.hmrc.fileupload.models.{ValidationException, Envelope}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

/**
  * Created by Josiah on 6/3/2016.
  */
class EnvelopeServiceSpec extends ActorSpec{

  import scala.language.postfixOps
  import EnvelopeService._
  val MAX_TIME_TO_LIVE = 2

	val storage = TestActorRef[ActorStub]
	val IdGenerator = TestActorRef[ActorStub]
	val marshaller = TestActorRef[ActorStub]
  val envelopMgr = system.actorOf(EnvelopeService.props(storage, IdGenerator, marshaller, MAX_TIME_TO_LIVE))
	implicit val ec = system.dispatcher


	"An EnvelopeService" should  {
		"respond with an envelope when it receives a GetEnvelop message" in {
			within(timeout){
	      val envelope = Support.envelope
	      val json = Json.toJson[Envelope](envelope)
	      val id = envelope._id.stringify

	      marshaller.underlyingActor.setReply(json)
	      storage.underlyingActor.setReply(Some(envelope))

	      envelopMgr ! GetEnvelope(id)
	      expectMsg(json)
      }
    }
		"respond with a BadRequestException when it receives a GetEnvelope message with an invalid id" in {
			within(timeout){
	      val id = BSONObjectID.generate.stringify

	      storage.underlyingActor.setReply(None)

	      envelopMgr ! GetEnvelope(id)
	      expectMsg(new BadRequestException(s"no envelope exists for id:$id"))
      }
    }
  }


	"An EnvelopeService" should {
		"respond with id  of created envelope when it receives a CreateEnvelope message" in {
			within(timeout) {
				val rawData = Support.envelopeBody
				val id = BSONObjectID.generate

				IdGenerator.underlyingActor.setReply(id)
				storage.underlyingActor.setReply(id)

				envelopMgr ! CreateEnvelope( rawData )

				expectMsg(id)
			}
		}
	}

	"An EnvelopeService" should  {
		"respond with Success after deleting an envelope" in {
			within(timeout){
				val id = "5752051b69ff59a8732f6474"
				storage.underlyingActor.setReply(true)
				envelopMgr ! DeleteEnvelope(id)
				expectMsg(true)
			}
		}
	}

	import scala.language.implicitConversions
	implicit def timeoutToDuration(timeout: Timeout): FiniteDuration = timeout.duration
}