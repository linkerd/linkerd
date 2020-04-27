package io.buoyant.grpc.gen

import com.google.protobuf.DescriptorProtos._
import scala.jdk.CollectionConverters._

case class ProtoFile(
  fileName: String,
  protoPkg: String,
  scalaPkg: String,
  outerObj: Option[String],
  enums: Seq[ProtoFile.EnumType],
  messages: Seq[ProtoFile.MessageType],
  services: Seq[ProtoFile.Service]
) {

  val scalaPrefix: String = outerObj match {
    case None => scalaPkg
    case Some(obj) => s"${scalaPkg}.${obj}"
  }
}

object ProtoFile {

  def mk(fd: FileDescriptorProto): ProtoFile = {
    val opts = fd.getOptions
    val protoPkg = fd.getPackage match {
      case p if p.startsWith(".") => p
      case p => s".${p}"
    }
    val scalaPkg = Option(opts.getJavaPackage).filter(_.nonEmpty)
      .getOrElse(fd.getPackage.stripPrefix("."))
    val outerObj = Option(opts.getJavaOuterClassname).filter(_.nonEmpty)
    val enums = fd.getEnumTypeList.asScala.map(EnumType.mk(_))
    val messages = fd.getMessageTypeList.asScala.map(MessageType.mk(_))
    val services = fd.getServiceList.asScala.map(Service.mk(_))
    ProtoFile(fd.getName, protoPkg, scalaPkg, outerObj, enums.toSeq, messages.toSeq, services.toSeq)
  }

  case class EnumType(
    name: String,
    values: Seq[EnumValue]
  )

  object EnumType {
    def mk(e: EnumDescriptorProto): EnumType = {
      val values = e.getValueList.asScala.map(EnumValue.mk(_))
      EnumType(e.getName, values.toSeq)
    }
  }

  case class EnumValue(
    name: String,
    number: Int
  )

  object EnumValue {
    def mk(v: EnumValueDescriptorProto): EnumValue =
      EnumValue(v.getName, v.getNumber)
  }

  case class MessageType(
    name: String,
    fields: Seq[Field],
    oneofs: Map[String, Oneof],
    enums: Seq[EnumType],
    messages: Seq[MessageType],
    isMap: Boolean
  )

  object MessageType {
    def mk(mt: DescriptorProto): MessageType = {
      val isMap = mt.getOptions.getMapEntry
      val messages = mt.getNestedTypeList.asScala.map(MessageType.mk(_))
      val mapMessages = messages.filter(_.isMap)

      val oneofFields = mt.getFieldList.asScala
        .filter(_.hasOneofIndex)
        .groupBy { f => mt.getOneofDecl(f.getOneofIndex) }
      val oneofs = oneofFields.map {
        case (o, fs) =>
          val name = o.getName
          val fields = fs.map(Field.mk(_, mapMessages.toSeq))
          name -> Oneof(name, fields.toSeq)
      }

      val fields = mt.getFieldList.asScala.filterNot(_.hasOneofIndex).map(Field.mk(_, mapMessages.toSeq))
      val enums = mt.getEnumTypeList.asScala.map(EnumType.mk(_))
      MessageType(mt.getName, fields.toSeq, oneofs, enums.toSeq, messages.toSeq, isMap)
    }
  }

  case class Oneof(
    name: String,
    fields: Seq[Field]
  )

  case class Field(
    number: Int,
    name: String,
    typeRef: TypeRef,
    isRepeated: Boolean,
    isMap: Boolean
  )

  object Field {
    def mk(f: FieldDescriptorProto, mapMessages: Seq[ProtoFile.MessageType]): Field = {
      val isRepeated = f.getLabel == FieldDescriptorProto.Label.LABEL_REPEATED
      val (typeRef, isMap) = f.getType match {
        case FieldDescriptorProto.Type.TYPE_ENUM => (TypeRef.Enum(f.getTypeName), false)
        case FieldDescriptorProto.Type.TYPE_MESSAGE =>
          val isSeqOfMapEntries = isRepeated && mapMessages.exists(d => f.getTypeName.endsWith(d.name))
          (TypeRef.Message(f.getTypeName), isSeqOfMapEntries)
        case kind => (TypeRef.Simple(kind), false)
      }
      Field(f.getNumber, f.getName, typeRef, isRepeated, isMap)
    }
  }

  sealed trait TypeRef
  object TypeRef {
    case class Enum(name: String) extends TypeRef
    case class Message(name: String) extends TypeRef
    case class Simple(kind: FieldDescriptorProto.Type) extends TypeRef
  }

  case class Service(
    name: String,
    rpcs: Seq[Rpc]
  )

  object Service {
    def mk(s: ServiceDescriptorProto): Service = {
      val rpcs = s.getMethodList.asScala.map { m =>
        val in = m.getInputType
        val c = if (m.getClientStreaming) Io.Stream(in) else Io.Unary(in)

        val out = m.getOutputType
        val s = if (m.getServerStreaming) Io.Stream(out) else Io.Unary(out)

        Rpc(m.getName, c, s)
      }

      Service(s.getName, rpcs.toSeq)
    }
  }

  case class Rpc(
    name: String,
    client: Io,
    server: Io
  )

  sealed trait Io { def kind: String }
  object Io {
    case class Unary(kind: String) extends Io
    case class Stream(kind: String) extends Io
  }
}
