package io.buoyant.k8s

import com.fasterxml.jackson.annotation.{JsonProperty, JsonSubTypes, JsonTypeInfo}
import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.k8s.{KubeObject => BaseObject}
import io.buoyant.test.Awaits
import org.scalatest.{FunSuite, OptionValues}

/**
 * This test both exercises the versioning functionality in `io.buoyant.k8s`, and provides
 * an example implementation. (See `namerd` for the current usage in the Buoyant suite of projects).
 */
class CustomResourceTest extends FunSuite with Awaits with OptionValues {
  import CustomResourceTest._

  object Fixtures {
    val bookList = Buf.Utf8("""{"kind":"BookList","apiVersion":"buoyant.io/vTest","metadata":{"selfLink":"/apis/buoyant.io/vTest/namespaces/test/books"},"items":[{"metadata":{"name":"programming-in-scala","selfLink":"/apis/buoyant.io/vTest/namespaces/test/books/programming-in-scala"},"title":"Programming in Scala","author":"Martin Odersky"}]} """)
  }

  test("namespaced: get books list") {
    import Fixtures._
    val service = Service.mk[Request, Response] { req =>
      assert(req.uri == s"/apis/buoyant.io/vTest/namespaces/test/books")
      val rsp = Response()
      rsp.version = req.version
      rsp.setContentTypeJson()
      rsp.headerMap("Transfer-Encoding") = "chunked"
      rsp.writer.write(bookList) before rsp.writer.close()
      Future.value(rsp)
    }

    val ns = Api(service).withNamespace("test")
    val books = await(ns.books.get()).value
    assert(books.items.length == 1)
    val book = books.items.head
    assert(book.title == "Programming in Scala")
  }
}

object CustomResourceTest {
  trait Object extends BaseObject

  case class Book(
    title: String,
    author: String,
    metadata: Option[ObjectMeta],
    apiVersion: Option[String],
    kind: Option[String]
  ) extends Object

  implicit object BookDescriptor extends ObjectDescriptor[Book, BookWatch] {
    def listName = "books"
    def toWatch(o: Book) = BookWatch.Modified(o)
  }

  case class BookList(
    @JsonProperty("items") items: Seq[Book],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends KubeList[Book]

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[BookWatch.Added], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[BookWatch.Modified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[BookWatch.Deleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[BookWatch.Error], name = "ERROR")
  ))
  sealed trait BookWatch extends Watch[Book]

  object BookWatch {

    case class Added(`object`: Book) extends BookWatch with Watch.Added[Book]

    case class Modified(`object`: Book) extends BookWatch with Watch.Modified[Book]

    case class Deleted(`object`: Book) extends BookWatch with Watch.Deleted[Book]

    case class Error(
      @JsonProperty(value = "object") status: Status
    ) extends BookWatch with Watch.Error[Book]

  }

  implicit private val bookTypeRef = new TypeReference[Book] {}
  implicit private val bookListTypeRef = new TypeReference[BookList] {}
  implicit private val bookWatchTypeRef = new TypeReference[BookWatch] {}
  implicit private val booksWatchIsOrdered =
    new ResourceVersionOrdering[Book, BookWatch]

  case class Api(client: Client) extends CustomResourceVersion[Object] {
    def owner = "buoyant.io"

    def ownerVersion = "vTest"

    override def withNamespace(ns: String) = new NsApi(client, ns)

    def books = listResource[Book, BookWatch, BookList]()
  }

  class NsApi(client: Client, ns: String) extends NsCustomResourceVersion[Object](client, "buoyant.io", "vTest", ns) {
    def books = listResource[Book, BookWatch, BookList]()
  }
}
