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

package uk.gov.hmrc.fileupload

import java.util.UUID

import akka.actor.ActorRef
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.joda.time.Duration
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{EssentialFilter, RequestHeader, Result}
import play.api.{Application, Configuration, Logger, Play}
import reactivemongo.api.commands
import uk.gov.hmrc.fileupload.controllers._
import uk.gov.hmrc.fileupload.controllers.routing.RoutingController
import uk.gov.hmrc.fileupload.controllers.transfer.TransferController
import uk.gov.hmrc.fileupload.file.zip.Zippy
import uk.gov.hmrc.fileupload.infrastructure.{DefaultMongoConnection, PlayHttp}
import uk.gov.hmrc.fileupload.read.envelope.{WithValidEnvelope, Service => EnvelopeService, _}
import uk.gov.hmrc.fileupload.read.file.{Service => FileService}
import uk.gov.hmrc.fileupload.read.notifier.{NotifierActor, NotifierRepository}
import uk.gov.hmrc.fileupload.read.stats.{Stats, StatsActor}
import uk.gov.hmrc.fileupload.testonly.TestOnlyController
import uk.gov.hmrc.fileupload.write.envelope._
import uk.gov.hmrc.fileupload.write.infrastructure.UnitOfWorkSerializer.{UnitOfWorkReader, UnitOfWorkWriter}
import uk.gov.hmrc.fileupload.write.infrastructure.{Aggregate, MongoEventStore, StreamId}
import uk.gov.hmrc.play.audit.filters.AuditFilter
import uk.gov.hmrc.play.auth.controllers.AuthParamsControllerConfig
import uk.gov.hmrc.play.auth.microservice.filters.AuthorisationFilter
import uk.gov.hmrc.play.config.{AppName, ControllerConfig, RunMode}
import uk.gov.hmrc.play.http.BadRequestException
import uk.gov.hmrc.play.http.logging.filters.LoggingFilter
import uk.gov.hmrc.play.microservice.bootstrap.DefaultMicroserviceGlobal

import scala.concurrent.{ExecutionContext, Future}

object ControllerConfiguration extends ControllerConfig {
  lazy val controllerConfigs = Play.current.configuration.underlying.as[Config]("controllers")
}

object AuthParamsControllerConfiguration extends AuthParamsControllerConfig {
  lazy val controllerConfigs = ControllerConfiguration.controllerConfigs
}

object MicroserviceAuditFilter extends AuditFilter with AppName {
  override def mat = Streams.Implicits.materializer
  override val auditConnector = MicroserviceAuditConnector

  override def controllerNeedsAuditing(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsAuditing
}

object MicroserviceLoggingFilter extends LoggingFilter {
  override def mat = Streams.Implicits.materializer
  override def controllerNeedsLogging(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsLogging
}

object MicroserviceAuthFilter extends AuthorisationFilter {
  override def mat = Streams.Implicits.materializer
  override lazy val authParamsConfig = AuthParamsControllerConfiguration
  override lazy val authConnector = MicroserviceAuthConnector

  override def controllerNeedsAuth(controllerName: String): Boolean = ControllerConfiguration.paramsForController(controllerName).needsAuth
}

object EnvelopeController extends EnvelopeController(
      nextId = () => EnvelopeId(UUID.randomUUID().toString),
      handleCommand = MicroserviceGlobal.envelopeCommandHandler,
      findEnvelope = MicroserviceGlobal.find,
      findMetadata = MicroserviceGlobal.findMetadata,
      findAllInProgressFile = MicroserviceGlobal.allInProgressFile )

object TestOnlyController extends TestOnlyController(
    MicroserviceGlobal.removeAllFiles,
    MicroserviceGlobal.removeAllEnvelopes,
    MicroserviceGlobal.eventStore,
    MicroserviceGlobal.statsRepository)

object RoutingController extends RoutingController(MicroserviceGlobal.envelopeCommandHandler)

object EventController extends EventController (
    MicroserviceGlobal.envelopeCommandHandler,
    MicroserviceGlobal.eventStore.unitsOfWorkForAggregate,
    MicroserviceGlobal.createReportHandler.handle(replay = true))

object FileController extends FileController(
      uploadBodyParser = MicroserviceGlobal.uploadBodyParser,
      retrieveFile = MicroserviceGlobal.retrieveFile,
      withValidEnvelope = MicroserviceGlobal.withValidEnvelope,
      handleCommand = MicroserviceGlobal.envelopeCommandHandler,
      clear = MicroserviceGlobal.fileRepository.clear() _)

object TransferController extends TransferController(
    MicroserviceGlobal.getEnvelopesByDestination,
    MicroserviceGlobal.envelopeCommandHandler,
    MicroserviceGlobal.zipEnvelope)


object MicroserviceGlobal extends DefaultMicroserviceGlobal with RunMode {

  override val auditConnector = MicroserviceAuditConnector

  override def microserviceMetricsConfig(implicit app: Application): Option[Configuration] = app.configuration.getConfig(s"microservice.metrics")

  override val loggingFilter = MicroserviceLoggingFilter

  override val microserviceAuditFilter = MicroserviceAuditFilter

  override val authFilter = None

  var subscribe: (ActorRef, Class[_]) => Boolean = _
  var publish: (AnyRef) => Unit = _

  lazy val db = DefaultMongoConnection.db

  lazy val auditedHttpExecute = PlayHttp.execute(auditConnector, appName, Some(t => Logger.warn(t.getMessage, t))) _

  lazy val envelopeRepository = uk.gov.hmrc.fileupload.read.envelope.Repository.apply(db)

  lazy val getEnvelope = envelopeRepository.get _

  lazy val withValidEnvelope = new WithValidEnvelope(getEnvelope)

  lazy val find = EnvelopeService.find(getEnvelope) _
  lazy val findMetadata = EnvelopeService.findMetadata(find) _

  lazy val sendNotification = NotifierRepository.notify(auditedHttpExecute) _

  lazy val fileRepository = uk.gov.hmrc.fileupload.read.file.Repository.apply(db)
  val iterateeForUpload = fileRepository.iterateeForUpload _
  val getFileFromRepo = fileRepository.retrieveFile _
  lazy val retrieveFile = FileService.retrieveFile(getFileFromRepo) _

  lazy val statsRepository = uk.gov.hmrc.fileupload.read.stats.Repository.apply(db)
  lazy val saveFileQuarantinedStat = Stats.save(statsRepository.insert) _
  lazy val deleteFileStoredStat = Stats.deleteFileStored(statsRepository.delete) _
  lazy val deleteVirusDetectedStat = Stats.deleteVirusDetected(statsRepository.delete) _
  lazy val allInProgressFile = Stats.all(statsRepository.all) _

  implicit val reader = new UnitOfWorkReader(EventSerializer.toEventData)
  implicit val writer = new UnitOfWorkWriter(EventSerializer.fromEventData)

  var eventStore: MongoEventStore = _

  // envelope read model
  lazy val createReportHandler = new EnvelopeReportHandler(
    toId = (streamId: StreamId) => EnvelopeId(streamId.value),
    envelopeRepository.update,
    envelopeRepository.delete,
    defaultState = (id: EnvelopeId) => uk.gov.hmrc.fileupload.read.envelope.Envelope(id))

  // command handler
  lazy val envelopeCommandHandler = {
    (command: EnvelopeCommand) =>
      new Aggregate[EnvelopeCommand, write.envelope.Envelope](
        handler = write.envelope.Envelope,
        defaultState = () => write.envelope.Envelope(),
        publish = publish,
        publishAllEvents = createReportHandler.handle(replay = false))(eventStore, defaultContext).handleCommand(command)
  }

  import play.api.libs.concurrent.Execution.Implicits._
  val uploadBodyParser = UploadParser.parse(iterateeForUpload) _

  val getEnvelopesByDestination = envelopeRepository.getByDestination _
  val zipEnvelope = Zippy.zipEnvelope(find, retrieveFile) _

  val removeAllEnvelopes = () => envelopeRepository.removeAll()
  val removeAllFiles = () => fileRepository.clear(Duration.ZERO)


  override def onStart(app: Application): Unit = {
    super.onStart(app)

    // event stream
    import play.api.Play.current
    import play.api.libs.concurrent.Akka
    val eventStream = Akka.system.eventStream
    subscribe = eventStream.subscribe
    publish = eventStream.publish

    // event store
    if (play.Play.isProd && app.configuration.getBoolean("Prod.mongodb.replicaSetInUse").getOrElse(true)) {
      eventStore = new MongoEventStore(db, writeConcern = commands.WriteConcern.ReplicaAcknowledged(n = 2, timeout = 5000, journaled = true))
    } else {
      eventStore = new MongoEventStore(db)
    }

    // notifier
    Akka.system.actorOf(NotifierActor.props(subscribe, find, sendNotification), "notifierActor")
    Akka.system.actorOf(StatsActor.props(subscribe, find, sendNotification, saveFileQuarantinedStat,
      deleteVirusDetectedStat, deleteFileStoredStat), "statsActor")

  }

  override def onBadRequest(request: RequestHeader, error: String): Future[Result] = {
    Future(ExceptionHandler(new BadRequestException(error)))(ExecutionContext.global)
  }

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    Future(ExceptionHandler(ex))(ExecutionContext.global)
  }

  override def microserviceFilters: Seq[EssentialFilter] = defaultMicroserviceFilters // ++ Seq(FileUploadValidationFilter)
}
