package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.parsing.PublicationMessageParser
import net.ripe.rpki.publicationserver.store.Migrations
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.`Content-Type`
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success}

class PublicationServiceActor(fsWriterFactory: ActorRefFactory => ActorRef)
  extends Actor with PublicationService {

  def actorRefFactory = context

  def receive = runRoute(publicationRoutes)

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1) {
    case _: Exception                =>
      SupervisorStrategy.Escalate
  }

  override def preStart() = {
      Migrations.migrate()
      val fsWriter = fsWriterFactory(context)
      init(fsWriter)
  }
}

object PublicationServiceActor {
  def props(actorRefFactory: ActorRefFactory => ActorRef) =
    Props(new PublicationServiceActor(actorRefFactory))
}

trait PublicationService extends HttpService with SnapshotStateService {

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = LoggerFactory.getLogger("PublicationService")

  val msgParser = wire[PublicationMessageParser]

  implicit val BufferedSourceUnmarshaller =
    Unmarshaller[BufferedSource](spray.http.ContentTypeRange.*) {
      case HttpEntity.NonEmpty(contentType, data) =>
        Source.fromInputStream(new ByteArrayInputStream(data.toByteArray), contentType.charset.nioCharset.toString)
      case HttpEntity.Empty => Source.fromInputStream(new ByteArrayInputStream(Array[Byte]()))
    }

  // we need to process all queries sequentially
  // so make this SingleThread EC for all Futures that handle queries
  implicit private val singleThreadEC = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

  val publicationRoutes =
    path("") {
      post {
        parameter("clientId") { clientId =>
          optionalHeaderValue(checkContentType) { ct =>
            serviceLogger.debug("Post request received")
            if (ct.isEmpty) {
              serviceLogger.warn("Request does not specify content-type")
            }
            respondWithMediaType(RpkiPublicationType) {
              entity(as[BufferedSource]) { xmlMessage =>
                onComplete {
                  Future(msgParser.parse(xmlMessage)) flatMap { parsedMessage =>
                    Future(processRequest(ClientId(clientId), parsedMessage))(executor = singleThreadEC)
                  }
                } {
                  case Success(result) =>
                    complete(result)
                  case Failure(error) =>
                    serviceLogger.error(s"Error processing POST request with clientId=$clientId", error)
                    complete(500, error.getMessage)
                }
              }
            }
          }
        }
      }
    }

  private def processRequest[T](clientId: ClientId, parsedMessage: Either[BaseError, T]) = {
    def logErrors(errors: Seq[ReplyPdu]): Unit = {
      serviceLogger.warn(s"Request contained ${errors.size} PDU(s) with errors:")
      errors.foreach { e =>
        serviceLogger.info(e.asInstanceOf[ReportError].message.getOrElse(s"Error code: ${e.asInstanceOf[ReportError].code.toString}"))
      }
    }

    val response = parsedMessage match {
      case Right(QueryMessage(pdus)) =>
        val elements = updateWith(clientId, pdus)
        elements.filter(_.isInstanceOf[ReportError]) match {
          case Seq() =>
            serviceLogger.info("Request handled successfully")
          case errors =>
            logErrors(errors)
        }
        ReplyMsg(elements).serialize

      case Right(ListMessage()) =>
        ReplyMsg(list(clientId)).serialize

      case Left(msgError) =>
        serviceLogger.warn("Error while handling request: {}", msgError)
        ErrorMsg(msgError).serialize
    }
    response
  }

  private def checkContentType(header: HttpHeader): Option[ContentType] = header match {
    case `Content-Type`(ct) =>
      if (!MediaTypeString.equals(ct.mediaType.toString())) {
        serviceLogger.warn("Request uses wrong media type: {}", ct.mediaType.toString())
      }
      Some(ct)
    case _ => None
  }
}


