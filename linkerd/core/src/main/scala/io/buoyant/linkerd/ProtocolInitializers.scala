package io.buoyant.linkerd

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.util.LoadService

/** A suite of protocol initializers. */
trait ProtocolInitializers {
  def names: Set[String]
  def get(name: String): Option[ProtocolInitializer]

  /**
   * Read a protocol name and attempt to load its [[ProtocolInitializer]].
   */
  def read(json: JsonParser): ProtocolInitializer
}

object ProtocolInitializers {

  /**
   * Concrete implementation of ProtocolInitializers, to that it may
   * change without breaking API.
   */
  private class Impl(seq: Seq[ProtocolInitializer]) extends ProtocolInitializers {
    override def toString = s"""ProtocolInitializers(${names.mkString(",")})"""
    def names = seq.map(_.name).toSet
    def get(name: String): Option[ProtocolInitializer] = seq.find(_.name == name)
    def read(json: JsonParser): ProtocolInitializer = ProtocolInitializers.read(json, this)
  }

  def apply(protos: Seq[ProtocolInitializer]): ProtocolInitializers =
    new Impl(protos)

  def apply(p: ProtocolInitializer, protos: ProtocolInitializer*): ProtocolInitializers =
    new Impl(p +: protos)

  val empty: ProtocolInitializers =
    new Impl(Seq.empty)

  /**
   * Runtime-loaded protocol initializers.
   *
   * Uses finagle's `LoadService` facility to discover protocol
   * support at runtime by searching the class path for
   * ProtocolInitializer subclasses.
   */
  def load(): ProtocolInitializers =
    new Impl(LoadService[ProtocolInitializer]())

  /**
   * Read a protocol name and attempt to load a
   * [[ProtocolInitializer]] from the given [[ProtocolInitializers]].
   */
  def read(json: JsonParser, protos: ProtocolInitializers): ProtocolInitializer =
    Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
      val name = json.getValueAsString
      json.nextToken()
      protos.get(name) match {
        case None => throw Parsing.error(s"Unknown protocol: $name", json)
        case Some(proto) => proto
      }
    }
}
