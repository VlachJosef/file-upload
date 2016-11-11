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
import javax.inject.Provider

import akka.actor.ActorRef
import com.kenshoo.play.metrics.MetricsController
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.joda.time.Duration
import play.api.ApplicationLoader.Context
import play.api._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import reactivemongo.api.commands
import uk.gov.hmrc.fileupload.admin.{Routes => AdminRoutes}
import uk.gov.hmrc.fileupload.app.{Routes => AppRoutes}
import uk.gov.hmrc.fileupload.controllers._
import uk.gov.hmrc.fileupload.controllers.routing.RoutingController
import uk.gov.hmrc.fileupload.controllers.transfer.TransferController
import uk.gov.hmrc.fileupload.file.zip.Zippy
import uk.gov.hmrc.fileupload.infrastructure._
import uk.gov.hmrc.fileupload.prod.Routes
import uk.gov.hmrc.fileupload.read.envelope.{WithValidEnvelope, Service => EnvelopeService, _}
import uk.gov.hmrc.fileupload.read.file.{Service => FileService}
import uk.gov.hmrc.fileupload.read.notifier.{NotifierActor, NotifierRepository}
import uk.gov.hmrc.fileupload.read.stats.{Stats, StatsActor}
import uk.gov.hmrc.fileupload.routing.{Routes => RoutingRoutes}
import uk.gov.hmrc.fileupload.testonly.TestOnlyController
import uk.gov.hmrc.fileupload.transfer.{Routes => TransferRoutes}
import uk.gov.hmrc.fileupload.write.envelope._
import uk.gov.hmrc.fileupload.write.infrastructure.UnitOfWorkSerializer.{UnitOfWorkReader, UnitOfWorkWriter}
import uk.gov.hmrc.fileupload.write.infrastructure.{Aggregate, MongoEventStore, StreamId}
import uk.gov.hmrc.play.audit.filters.AuditFilter
import uk.gov.hmrc.play.audit.http.config.ErrorAuditingSettings
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.auth.controllers.AuthParamsControllerConfig
import uk.gov.hmrc.play.auth.microservice.filters.AuthorisationFilter
import uk.gov.hmrc.play.config.{AppName, ControllerConfig}
import uk.gov.hmrc.play.graphite.{GraphiteConfig, GraphiteMetricsImpl}
import uk.gov.hmrc.play.http.logging.filters.LoggingFilter
import uk.gov.hmrc.play.microservice.bootstrap.Routing.RemovingOfTrailingSlashes
import uk.gov.hmrc.play.microservice.bootstrap.{JsonErrorHandling, MicroserviceFilters}


class ApplicationLoader extends play.api.ApplicationLoader {
  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    new ApplicationModule(context).application
  }
}

class ApplicationModule(context: Context) extends BuiltInComponentsFromContext(context)
  with AhcWSComponents with AppName with MicroserviceFilters
  with GraphiteConfig
  with RemovingOfTrailingSlashes
  with JsonErrorHandling
  with ErrorAuditingSettings {

  var subscribe: (ActorRef, Class[_]) => Boolean = _
  var publish: (AnyRef) => Unit = _
  var withBasicAuth: BasicAuth = _
  var eventStore: MongoEventStore = _

  implicit val reader = new UnitOfWorkReader(EventSerializer.toEventData)
  implicit val writer = new UnitOfWorkWriter(EventSerializer.fromEventData)

  def basicAuthConfiguration(config: Configuration): BasicAuthConfiguration = {
    def getUsers(config: Configuration): List[User] = {
      config.getString("basicAuth.authorizedUsers").map { s =>
        s.split(";").flatMap(
          user => {
            user.split(":") match {
              case Array(username, password) => Some(User(username, password))
              case _ => None
            }
          }
        ).toList
      }.getOrElse(List.empty)
    }

    config.getBoolean("feature.basicAuthEnabled").getOrElse(false) match {
      case true => BasicAuthEnabled(getUsers(config))
      case false => BasicAuthDisabled
    }
  }

  lazy val database = new ReactiveMongoConnector(configuration, applicationLifecycle)

  lazy val db = database.mongoConnector.db

  def onStart(): Unit = {
    subscribe = actorSystem.eventStream.subscribe
    publish = actorSystem.eventStream.publish

    // event store
    if (play.Environment.simple().isProd && configuration.getBoolean("Prod.mongodb.replicaSetInUse").getOrElse(true)) {
      eventStore = new MongoEventStore(db, writeConcern = commands.WriteConcern.ReplicaAcknowledged(n = 2, timeout = 5000, journaled = true))
    } else {
      eventStore = new MongoEventStore(db)
    }

    withBasicAuth = BasicAuth(basicAuthConfiguration(configuration))

    // notifier
    actorSystem.actorOf(NotifierActor.props(subscribe, find, sendNotification), "notifierActor")
    actorSystem.actorOf(StatsActor.props(subscribe, find, sendNotification, saveFileQuarantinedStat,
      deleteVirusDetectedStat, deleteFileStoredStat), "statsActor")

  }

  onStart()

  lazy val auditedHttpExecute = PlayHttp.execute(MicroserviceAuditFilter.auditConnector,
    appName, Some(t => Logger.warn(t.getMessage, t))) _

  lazy val envelopeRepository = uk.gov.hmrc.fileupload.read.envelope.Repository.apply(db)

  lazy val getEnvelope = envelopeRepository.get _

  lazy val withValidEnvelope = new WithValidEnvelope(getEnvelope)

  lazy val find = EnvelopeService.find(getEnvelope) _
  lazy val findMetadata = EnvelopeService.findMetadata(find) _

  lazy val sendNotification = NotifierRepository.notify(auditedHttpExecute, wsClient) _

  lazy val fileRepository = uk.gov.hmrc.fileupload.read.file.Repository.apply(db)
  val iterateeForUpload = fileRepository.iterateeForUpload _
  val getFileFromRepo = fileRepository.retrieveFile _
  lazy val retrieveFile = FileService.retrieveFile(getFileFromRepo) _

  lazy val statsRepository = uk.gov.hmrc.fileupload.read.stats.Repository.apply(db)
  lazy val saveFileQuarantinedStat = Stats.save(statsRepository.insert) _
  lazy val deleteFileStoredStat = Stats.deleteFileStored(statsRepository.delete) _
  lazy val deleteVirusDetectedStat = Stats.deleteVirusDetected(statsRepository.delete) _
  lazy val allInProgressFile = Stats.all(statsRepository.all) _

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

  lazy val envelopeController = {
    val nextId = () => EnvelopeId(UUID.randomUUID().toString)
    val getEnvelopesByStatus = envelopeRepository.getByStatus _
    new EnvelopeController(
      withBasicAuth = withBasicAuth,
      nextId = nextId,
      handleCommand = envelopeCommandHandler,
      findEnvelope = find,
      findMetadata = findMetadata,
      findAllInProgressFile = allInProgressFile,
      getEnvelopesByStatus = getEnvelopesByStatus)
  }

  lazy val eventController = {
    new EventController(envelopeCommandHandler, eventStore.unitsOfWorkForAggregate, createReportHandler.handle(replay = true))
  }

  lazy val fileController = {
    val uploadBodyParser = UploadParser.parse(iterateeForUpload) _
    new FileController(
      withBasicAuth = withBasicAuth,
      uploadBodyParser = uploadBodyParser,
      retrieveFile = retrieveFile,
      withValidEnvelope = withValidEnvelope,
      handleCommand = envelopeCommandHandler,
      clear = fileRepository.clear() _)
  }

  lazy val transferController = {
    val getEnvelopesByDestination = envelopeRepository.getByDestination _
    val zipEnvelope = Zippy.zipEnvelope(find, retrieveFile) _
    new TransferController(withBasicAuth, getEnvelopesByDestination, envelopeCommandHandler, zipEnvelope)
  }

  lazy val testOnlyController = {
    val removeAllEnvelopes = () => envelopeRepository.removeAll()
    val removeAllFiles = () => fileRepository.clear(Duration.ZERO)
    new TestOnlyController(removeAllFiles, removeAllEnvelopes, eventStore, statsRepository)
  }

  lazy val routingController = {
    new RoutingController(envelopeCommandHandler)
  }

  lazy val appRoutes = new AppRoutes(httpErrorHandler, new Provider[EnvelopeController] {
    override def get(): EnvelopeController = envelopeController
  },
    new Provider[FileController] {
      override def get(): FileController = fileController
    }, new Provider[EventController] {
      override def get(): EventController = eventController
    })

  lazy val transferRoutes = new TransferRoutes(httpErrorHandler, new Provider[TransferController] {
    override def get(): TransferController = transferController
  })
  lazy val routingRoutes = new RoutingRoutes(httpErrorHandler, new Provider[RoutingController] {
    override def get(): RoutingController = routingController
  })
  lazy val metricsController = new MetricsController(new GraphiteMetricsImpl(applicationLifecycle, configuration))
  lazy val adminRoutes = new AdminRoutes(httpErrorHandler, new Provider[MetricsController] {
    override def get(): MetricsController = metricsController
  })

  lazy val router: Router = new Routes(httpErrorHandler, appRoutes, transferRoutes, routingRoutes,
    health.Routes, adminRoutes)


  object ControllerConfiguration extends ControllerConfig {
    lazy val controllerConfigs = configuration.underlying.as[Config]("controllers")
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

  override def loggingFilter: LoggingFilter = MicroserviceLoggingFilter

  override def microserviceAuditFilter: AuditFilter = MicroserviceAuditFilter

  override def authFilter: Option[EssentialFilter] = None

  override def microserviceMetricsConfig(implicit app: Application): Option[Configuration] = configuration.getConfig(s"microservice.metrics")

  override def auditConnector: AuditConnector = MicroserviceAuditConnector
}