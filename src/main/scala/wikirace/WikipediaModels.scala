package wikirace

import io.circe.{Decoder, HCursor}

sealed abstract class WikipediaProp(val name: String,
                                    val ns: String) extends Product with Serializable

object WikipediaProp {
  final case object Links extends WikipediaProp("links", "pl")
  final case object LinksHere extends WikipediaProp("linkshere", "lh")
}

final case class WikipediaPage(prop: WikipediaProp, title: String, links: List[String])
final case class WikipediaResponse(pages: List[WikipediaPage], continue: Option[String])

object WikipediaResponse {

  def decode(prop: WikipediaProp): Decoder[WikipediaResponse] = {
    val linksListDecoder = Decoder.decodeList[String]((c: HCursor) => for {
      title <- c.get[String]("title")
    } yield title)

    val pageListDecoder = Decoder.decodeList[WikipediaPage]((c: HCursor) => for {
      title <- c.get[String]("title")
      links <- c.getOrElse(prop.name)(List.empty[String])(linksListDecoder)
    } yield WikipediaPage(prop, title, links))

    (c: HCursor) => for {
      pages <- c.downField("query").get("pages")(pageListDecoder)
      continue <- c.downField("continue").getOrElse(s"${prop.ns}continue")("").map {
        case "" => None
        case c => Some(c)
      }
    } yield WikipediaResponse(pages, continue)
  }
}
