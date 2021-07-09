import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive, ValidationRejection}
import akka.pattern.StatusReply
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import wikirace.{WikipediaApi, WikipediaGraph}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn

object Main {
  final case class SearchWikipedia(start: String, end: String, replyTo: ActorRef[StatusReply[List[String]]])

  def apply(): Behavior[SearchWikipedia] = Behaviors.setup { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val ec: ExecutionContext = context.executionContext
    implicit val api: WikipediaApi[Future] = WikipediaApi()

    Behaviors.receiveMessage {
      case SearchWikipedia(start, end, replyTo) =>
        context.spawnAnonymous(WikipediaGraph(start, end, replyTo))
        Behaviors.same
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[SearchWikipedia] = ActorSystem(Main(), "wikirace")
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = 1.minute

    val validatedQueryParams: Directive[(String, String)] = parameters("start", "end").tflatMap {
      case ("", _) => reject(ValidationRejection("\"start\" must not be an empty string"))
      case (_, "") => reject(ValidationRejection("\"end\" must not be an empty string"))
      case (start, end) if start == end => reject(ValidationRejection("\"start\" and \"end\" must not be the same value"))
      case params => tprovide(params)
    }

    val route =
      get {
        (path("api" / "wikirace") & validatedQueryParams) { (start, end) =>
          onSuccess(system.askWithStatus(SearchWikipedia(start, end, _))) {
            complete(_)
          }
        }
      }

    val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine()
    bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
  }
}
