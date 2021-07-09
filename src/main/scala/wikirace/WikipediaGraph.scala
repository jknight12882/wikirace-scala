package wikirace

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.pattern.StatusReply
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.{Done, NotUsed}
import cats.Monad

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object WikipediaGraph {
  sealed trait WikipediaGraphMessage

  final case class WikipediaGraphLinks(page: WikipediaPage,
                                       promise: Promise[Seq[String]]) extends WikipediaGraphMessage

  final case object WikipediaGraphComplete extends WikipediaGraphMessage
  final case class WikipediaGraphError(error: Throwable) extends WikipediaGraphMessage

  final case object WikipediaGraphStreamAborted extends RuntimeException("WikipediaGraph terminated")

  def apply(start: String, end: String, replyTo: ActorRef[StatusReply[List[String]]])
           (implicit mat: Materializer, ec: ExecutionContext, api: WikipediaApi[Future]): Behavior[WikipediaGraphMessage] = {

    import api._

    Behaviors.setup { context =>
      val killswitch = KillSwitches.shared("wikipediagraph-killswitch")

      val sink = askGraph(context.self)
        .filterNot(_.isEmpty)
        .via(killswitch.flow)
        .toMat(Sink.collection)(Keep.right)

      Future.firstCompletedOf(
        traverseGraph(start, mapTitlesToLinks(getLinks).toMat(sink)(Keep.right)) ::
        traverseGraph(end, mapTitlesToLinks(getLinksHere).toMat(sink)(Keep.right)) ::
        Nil
      ).onComplete {
        case Success(_) => context.self ! WikipediaGraphComplete
        case Failure(WikipediaGraphStreamAborted) => // Do nothing
        case Failure(e) => context.self ! WikipediaGraphError(e)
      }

      val lhs = mutable.HashMap(start -> "")
      val rhs = mutable.HashMap(end -> "")

      Behaviors.receiveMessage[WikipediaGraphMessage] {
        case WikipediaGraphLinks(WikipediaPage(prop, title, links), p) =>
          (prop match {
            case WikipediaProp.Links => calculateVertices(title, links)(lhs, rhs)
            case WikipediaProp.LinksHere => calculateVertices(title, links)(rhs, lhs)
          }).fold({ links =>
            p.success(links)
            Behaviors.same
          }, { midpoint =>
            val path = ListBuffer(midpoint)
            lhs.walkPath(midpoint)(path.prepend)
            rhs.walkPath(midpoint)(path.append)
            replyTo ! StatusReply.success(path.toList)
            Behaviors.stopped
          })

        case WikipediaGraphComplete =>
          replyTo ! StatusReply.success(Nil)
          Behaviors.stopped

        case WikipediaGraphError(e) =>
          replyTo ! StatusReply.error(e)
          Behaviors.stopped
      }
      .receiveSignal {
        case (_, PostStop) =>
          killswitch.abort(WikipediaGraphStreamAborted)
          Behaviors.same
      }
    }
  }

  def mapTitlesToLinks(f: (Seq[String], Option[String]) => Future[WikipediaResponse])
                      (implicit ec: ExecutionContext): Flow[String, WikipediaPage, NotUsed] =

    Flow[String]
      .grouped(50)
      .flatMapConcat { titles =>
        Source.unfoldAsync[Either[Done, Option[String]], Source[WikipediaPage, NotUsed]](Right(None)) {
          case Left(_) => Future.successful(None)

          case Right(continue) => f(titles, continue).map {
            case WikipediaResponse(pages, continue) =>
              val step = if (continue.isEmpty) Left(Done) else Right(continue)
              val source = Source.fromIterator(() => pages.iterator.filterNot(_.links.isEmpty))
              Some(step -> source)
          }
        }
      }
      .flatMapConcat(identity)

  def traverseGraph(start: String, sink: Sink[String, Future[Seq[Seq[String]]]])
                   (implicit ec: ExecutionContext, mat: Materializer, M: Monad[Future]): Future[Done] =

    M.tailRecM(Source.single(start))(_.runWith(sink).map { links =>
      if (links.isEmpty) Right(Done)
      else Left(Source.fromIterator(() => links.iterator.flatten))
    })

  def askGraph(ref: ActorRef[WikipediaGraphLinks]): Flow[WikipediaPage, Seq[String], NotUsed] =
    Flow[WikipediaPage].mapAsyncUnordered(1) { el =>
      val p = Promise[Seq[String]]()
      ref ! WikipediaGraphLinks(el, p)
      p.future
    }

  def calculateVertices(title: String, links: List[String])
                       (lhs: mutable.Map[String, String], rhs: mutable.Map[String, String]): WikipediaGraphResult = {

    @tailrec
    def calculateVerticesRecursive(links: List[String], buffer: ListBuffer[String]): WikipediaGraphResult = links match {
      case Nil => Left(buffer.toList)

      case link :: rest if (link == title) || lhs.contains(link) =>
        calculateVerticesRecursive(rest, buffer)

      case link :: rest =>
        lhs += (link -> title)
        if (rhs.contains(link)) Right(link)
        else calculateVerticesRecursive(rest, buffer += link)
    }

    calculateVerticesRecursive(links, ListBuffer.empty)
  }

  implicit final class MutableMapOps(val map: collection.Map[String, String]) extends AnyVal {

    @tailrec
    def walkPath(key: String)(f: String => Unit): Unit = {
      val value = map(key)

      if (value.nonEmpty) {
        f(value)
        walkPath(value)(f)
      }
    }
  }
}
