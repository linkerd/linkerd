package io.buoyant.linkerd

import com.fasterxml.jackson.core.{JsonParseException, JsonParser, JsonToken}
import com.twitter.finagle.Stack
import scala.annotation.tailrec

/**
 * A few utilities for interacting with Jackson.
 */
object Parsing {

  class Error(json: JsonParser, msg: String)
    extends JsonParseException(msg, json.getCurrentLocation)

  case class UnexpectedToken(
    name: Option[String],
    observed: Option[JsonToken],
    expected: JsonToken,
    json: JsonParser
  ) extends Error(json, (name, observed) match {
    case (None, None) => s"expected '$expected'; empty"
    case (None, Some(current)) => s"expected '$expected'; found '$current'"
    case (Some(name), None) => s"'$name' must be '$expected'; empty"
    case (Some(name), Some(current)) => s"'$name' must be '$expected'; found '$current'"
  })

  /** Error helper */
  def error(msg: String, json: JsonParser): Error = new Error(json, msg)

  /** Error if the parser is not on the given token, or run the provided function. */
  def ensureTok[T](json: JsonParser, tok: JsonToken)(f: JsonParser => T): T =
    (json.getCurrentName, json.getCurrentToken) match {
      case (_, cur) if cur == tok => f(json)
      case (name, cur) => throw new UnexpectedToken(Option(name), Option(cur), tok, json)
    }

  /** Read an object field-by-field, folding over T-typed values. */
  def foldObject[T](json: JsonParser, init: T)(f: (T, String, JsonParser) => T): T =
    ensureTok(json, JsonToken.START_OBJECT) { json =>
      @tailrec def loop(accum: T): T =
        json.getCurrentToken() match {
          case JsonToken.FIELD_NAME =>
            val key = json.getCurrentName
            json.nextToken()
            loop(f(accum, key, json))
          case JsonToken.END_OBJECT =>
            json.nextToken()
            accum
          case tok => throw error(s"invalid object field; found $tok", json)
        }
      json.nextToken()
      loop(init)
    }

  def findInObject[T](json: JsonParser)(f: PartialFunction[(String, JsonParser), T]): Option[T] =
    foldObject[Option[T]](json, None) {
      case (None, field, json) if f.isDefinedAt((field, json)) =>
        Some(f((field, json)))
      case (ret, field, json) =>
        skipValue(json)
        ret
    }

  def skipValue(json: JsonParser): Unit =
    json.getCurrentToken match {
      case JsonToken.START_ARRAY | JsonToken.START_OBJECT =>
        json.skipChildren()
        json.nextToken()
      case tok =>
        json.nextToken()
    }

  /** Read an object item-by-item, folding over T-typed items. */
  def foldArray[T](json: JsonParser, init: T)(f: (T, JsonParser) => T): T =
    ensureTok(json, JsonToken.START_ARRAY) { json =>
      @tailrec def loop(init: T): T =
        json.getCurrentToken match {
          case JsonToken.END_ARRAY =>
            json.nextToken()
            init
          case _ => loop(f(init, json))
        }
      json.nextToken()
      loop(init)
    }

  /**
   * A generic parser for Stack.Params.
   */
  trait Params {
    /** Supported param names */
    def keys: Set[String]

    /** Update `params` by reading a `Stack.Param` from configuration */
    def read(key: String, json: JsonParser, params: Stack.Params): Stack.Params

    def readObject(json: JsonParser, params: Stack.Params): Stack.Params = {
      val kind = json.getCurrentName
      Parsing.foldObject(json, params) {
        case (params, key, json) if keys(key) => read(key, json, params)
        case (_, key, json) =>
          throw Parsing.error(s"unexpected $kind param '$key'", json)
      }
    }

    final def andThen(other: Params, others: Params*): Params = {
      val all = (this +: other +: others).foldLeft(Seq.empty[Params]) {
        case (all, Params.Join(parsers)) => all ++ parsers
        case (all, parser) => all :+ parser
      }
      new Params.Join(all)
    }
  }

  object Params {
    object Empty extends Params {
      val keys = Set.empty[String]
      def read(k: String, j: JsonParser, params: Stack.Params) = params
    }

    private case class Join(all: Seq[Params]) extends Params {
      val keys = all.foldLeft(Set.empty[String]) { case (ks, p) => ks ++ p.keys }
      def read(key: String, json: JsonParser, params: Stack.Params): Stack.Params =
        all.foldLeft(params) {
          case (params, parser) if parser.keys(key) => parser.read(key, json, params)
          case (params, _) => params
        }
    }

    def apply(parser: Params, parsers: Params*): Params =
      Join(parser +: parsers)
  }

  /** Parses a single param */
  abstract class Param(val key: String) extends Params {
    val keys = Set(key)
    def read(json: JsonParser, params: Stack.Params): Stack.Params
    def read(k: String, json: JsonParser, params: Stack.Params): Stack.Params =
      if (k == key) read(json, params) else params
  }

  object Param {
    def apply(key: String)(mk: (JsonParser, Stack.Params) => Stack.Params): Param =
      new Param(key) {
        def read(json: JsonParser, params: Stack.Params) = mk(json, params)
      }

    def Boolean[P: Stack.Param](key: String)(f: Boolean => P): Param =
      new Param(key) {
        def read(json: JsonParser, params: Stack.Params) =
          json.getCurrentToken match {
            case JsonToken.VALUE_TRUE =>
              json.nextToken()
              params + f(true)
            case JsonToken.VALUE_FALSE =>
              json.nextToken()
              params + f(false)
            case tok => throw error(s"'$key' must have a boolean value; found '$tok'", json)
          }
      }

    def Long[P: Stack.Param](key: String)(f: Long => P): Param =
      new Param(key) {
        def read(json: JsonParser, params: Stack.Params) =
          Parsing.ensureTok(json, JsonToken.VALUE_NUMBER_INT) { json =>
            val v = json.getLongValue
            json.nextToken()
            params + f(v)
          }
      }

    def Int[P: Stack.Param](key: String)(f: Int => P): Param =
      new Param(key) {
        def read(json: JsonParser, params: Stack.Params) =
          Parsing.ensureTok(json, JsonToken.VALUE_NUMBER_INT) { json =>
            val v = json.getIntValue
            json.nextToken()
            params + f(v)
          }
      }

    def Text[P: Stack.Param](key: String)(f: String => P): Param =
      new Param(key) {
        def read(json: JsonParser, params: Stack.Params) =
          Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
            val text = json.getText
            json.nextToken()
            params + f(text)
          }
      }
  }
}
