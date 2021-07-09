package wikirace

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.{HttpEncodings, `Accept-Encoding`, `User-Agent`}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.{ExecutionContext, Future}

trait WikipediaApi[F[_]] {
  def getLinks(title: Seq[String], continue: Option[String] = None): F[WikipediaResponse]

  def getLinksHere(title: Seq[String], continue: Option[String] = None): F[WikipediaResponse]
}

object WikipediaApi {
  def apply()(implicit provider: ClassicActorSystemProvider): WikipediaApi[Future] =
    futureInstance(Http().singleRequest(_))(Materializer.matFromSystem(provider), provider.classicSystem.dispatcher)

  def futureInstance(singleRequest: HttpRequest => Future[HttpResponse])
                    (implicit mat: Materializer, ec: ExecutionContext): WikipediaApi[Future] = {

    val apiUrl = Uri("https://en.wikipedia.org/w/api.php")

    val headers = List(
      `User-Agent`("wikirace/0.1.0 (https://github.com/jknight12882/wikirace-scala; jknight12882@gmail.com)"),
      `Accept-Encoding`(HttpEncodings.gzip, HttpEncodings.deflate)
    )

    val linksUnmarshaller = FailFastCirceSupport.unmarshaller(
      WikipediaResponse.decode(WikipediaProp.Links)
    )

    val linksHereUnmarshaller = FailFastCirceSupport.unmarshaller(
      WikipediaResponse.decode(WikipediaProp.LinksHere)
    )

    def decodeResponse(response: HttpResponse): HttpResponse =
      (response.encoding match {
        case HttpEncodings.gzip => Coders.Gzip
        case HttpEncodings.deflate => Coders.Deflate
        case _ => Coders.NoCoding
      }).decodeMessage(response)

    def makeRequest(prop: WikipediaProp, titles: Seq[String], continue: Option[String])
                   (unmarshaller: FromEntityUnmarshaller[WikipediaResponse]): Future[WikipediaResponse] = {

      val query = continue.foldLeft(Uri.Query(
        "action" -> "query",
        "format" -> "json",
        "formatversion" -> "2",
        "titles" -> titles.mkString("|"),
        "prop" -> prop.name,
        s"${prop.ns}limit" -> "max",
        s"${prop.ns}namespace" -> "0|14|100",
      )) { (query, c) =>
        (s"${prop.ns}continue" -> c) +: query
      }

      singleRequest(HttpRequest(uri = apiUrl.withQuery(query), headers = headers)).flatMap { response =>
        unmarshaller(decodeResponse(response).entity)
      }
    }

    new WikipediaApi[Future] {
      override def getLinks(title: Seq[String], continue: Option[String]): Future[WikipediaResponse] =
        makeRequest(WikipediaProp.Links, title, continue)(linksUnmarshaller)

      override def getLinksHere(title: Seq[String], continue: Option[String]): Future[WikipediaResponse] =
        makeRequest(WikipediaProp.LinksHere, title, continue)(linksHereUnmarshaller)
    }
  }
}
