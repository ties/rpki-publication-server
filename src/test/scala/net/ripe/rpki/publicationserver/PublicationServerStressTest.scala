package net.ripe.rpki.publicationserver

import java.util.UUID

import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.FSWriterActor
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class PublicationServerStressTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing {

  // Use the production actorsystem for the fsWriterActor and not the one from ScalatestRouterTest, because the latter is single threaded!
  val fsWriterRef = system.actorOf(FSWriterActor.props)

  def actorRefFactory = system

  trait Context {
    def actorRefFactory = system
  }

  lazy val publicationService = {
    val service = new PublicationService with Context
    service.init(fsWriterRef)
    service
  }

  val objectStore = new ObjectStore

  val listXml = getFile("/list.xml").mkString

  before {
    objectStore.clear()
  }

  def publishAndRetrieve(clientId: ClientId, promise: Promise[Unit]) = {
    val content = UUID.randomUUID.toString.replace("-", "0")
    val uri = "rsync://" + clientId.value
    val expectedListResponse = getListResponse(uri, hash(Base64(content)))
    val publishRequest = getPublishRequest(uri, content)

    POST(s"/?clientId=${clientId.value}", publishRequest) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      POST(s"/?clientId=${clientId.value}", listXml) ~> publicationService.publicationRoutes ~> check {
        val response = responseAs[String]
        trim(response) should be(trim(expectedListResponse))
        promise.success(())
      }
    }
  }

  val oneSecond = 1000000000L

  test("should get correct result for one client request") {
    val futures = getPublishRetrieveFutures(1)
    futures.foreach(f => Await.ready(f, Duration.fromNanos(oneSecond * 1)))
  }

  test("should get correct results for 100 sequential client requests") {
    val futures = getPublishRetrieveFutures(100)
    futures.foreach(f => {
      Await.ready(f, Duration.fromNanos(oneSecond * 10))
    })
  }

  test("should get correct results for 100 parallel client requests") {
    val futures = getPublishRetrieveFutures(100)
    val futureSequence = Future.sequence(futures)
    Await.ready(futureSequence, Duration.fromNanos(oneSecond * 100))
  }

  def getPublishRetrieveFutures(nr: Int) = {
    val futures = (0 until nr).map(i => {
      val promise = Promise[Unit]()
      Future {
        publishAndRetrieve(ClientId(i.toString), promise)
      }
      promise.future
    })
    futures
  }

  def getPublishRequest(uri: String, content: String) =
    s"""
       |<msg
       |   type="query"
       |   version="3"
       |   xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
       | <publish
       |     uri="$uri">
       | $content
        | </publish>
        |</msg>
    """.stripMargin

  def getListResponse(uri: String, hash: Hash) =
    s"""
       |<msg
       |       type="reply"
       |       version="3"
       |       xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
       |   <list uri="$uri"
       |         hash="${hash.hash}"/>
       |</msg>
    """.stripMargin
}