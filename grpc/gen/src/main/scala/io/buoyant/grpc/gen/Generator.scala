package io.buoyant.grpc.gen

import com.google.protobuf.DescriptorProtos._
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type._
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import com.twitter.util.{Return, Throw, Try}
import io.buoyant.grpc.gen.ProtoFile.TypeRef
import java.io.InputStream
import scala.collection.JavaConverters._

/**
 * Generates Twitter-style scala bindings for proto3/gRPC.
 */
object Generator {

  /**
   * Helper to read a byte stream as a protobuf CodeGeneratorRequest
   */
  def parseRequest(in: InputStream): CodeGeneratorRequest =
    CodeGeneratorRequest.parseFrom(in)

  private[this] object GrpcRequired extends Exception("`plugins=grpc` must be set")

  /**
   * Generate Finage bindings.
   *
   * `plugins=grpc` must be set
   */
  def gen(req: CodeGeneratorRequest): CodeGeneratorResponse = {
    val files = Try {
      val noGrpc = (req.getParameter != "plugins=grpc")
      val protos = req.getProtoFileList.asScala.map(ProtoFile.mk(_))
      if (noGrpc && protos.exists(_.services.nonEmpty)) throw GrpcRequired

      val typeMap = mkTypeMap(protos)
      protos.map { proto =>
        val name = {
          val dir = proto.scalaPkg.replace(".", "/")
          val name = proto.outerObj.getOrElse(proto.fileName.split("/").last.stripSuffix(".proto"))
          s"${dir}/${name}.pb.scala"
        }
        CodeGeneratorResponse.File.newBuilder
          .setName(name)
          .setContent(genFile(proto, typeMap))
          .build()
      }
    }

    val rsp = CodeGeneratorResponse.newBuilder
    files match {
      case Throw(exc) => rsp.setError(exc.getMessage)
      case Return(files) => rsp.addAllFile(files.toIterable.asJava)
    }
    rsp.build()
  }

  /*
   * And now for some low down and dirty codegen...
   */

  // In order to prevent namespace collisions, we don't import
  // anything into generated files.  Instead, we save everything that
  // would be imported as a static string to be interpolated.
  private[this] val BUF = "com.twitter.io.Buf"
  private[this] val H2PKG = "com.twitter.finagle.buoyant.h2"
  private[this] val PBPKG = "com.google.protobuf"
  private[this] val PBIS = s"${PBPKG}.CodedInputStream"
  private[this] val PBOS = s"${PBPKG}.CodedOutputStream"
  private[this] val RTPKG = "io.buoyant.grpc.runtime"
  private[this] val WFMT = s"${PBPKG}.WireFormat"
  private[this] val WIRET = s"$WFMT.WIRETYPE"

  private[this] def genFile(proto: ProtoFile, typeMap: String => String): String = {
    val header =
      s"""|package ${proto.scalaPkg}
          |
          |/*
          | * Generated from ${proto.fileName} by io.buoyant.grpc.gen.
          | *
          | * You don't want to edit this.
          | */
          |""".stripMargin

    def genBody(indent: String): String = {
      val protoPkg = proto.protoPkg
      val scope = proto.scalaPrefix
      val enums = proto.enums match {
        case Nil => ""
        case enums =>
          enums.map(genEnum(_, indent)).mkString("\n", "\n", "")
      }
      val msgs = proto.messages match {
        case Nil => ""
        case msgs =>
          msgs.map(genMessageType(_, typeMap, scope, indent)).mkString("\n", "\n", "")
      }
      val svcs = proto.services match {
        case Nil => ""
        case svcs =>
          svcs.map(genService(_, typeMap, protoPkg, scope, indent)).mkString("\n", "\n", "")
      }
      s"${enums}${msgs}${svcs}"
    }

    proto.outerObj match {
      case Some(obj) =>
        s"""|${header}
            |object ${obj} {${genBody("  ")}}
            |""".stripMargin

      case None =>
        s"""|${header}
            |${genBody("")}
            |""".stripMargin
    }
  }

  private[this] def mkTypeMap(protos: Seq[ProtoFile]): Map[String, String] = {
    def enumTypes(e: ProtoFile.EnumType, pname: String, sname: String): Map[String, String] =
      Map(s"${pname}.${e.name}" -> s"${sname}.${e.name}")

    def messageTypes(m: ProtoFile.MessageType, pname0: String, sname0: String): Map[String, String] = {
      val pname = s"${pname0}.${m.name}"
      val sname = s"${sname0}.${m.name}"
      val enums = m.enums.flatMap(enumTypes(_, pname, sname)).toMap
      Map(pname -> sname) ++ enums ++ m.messages.flatMap(messageTypes(_, pname, sname))
    }

    protos.flatMap { p =>
      p.enums.flatMap(enumTypes(_, p.protoPkg, p.scalaPrefix)) ++
        p.messages.flatMap(messageTypes(_, p.protoPkg, p.scalaPrefix))
    }.toMap
  }

  private[this] def genEnum(enum: ProtoFile.EnumType, indent: String): String = {
    val decoders = enum.values.map { v => s"case ${v.number} => ${v.name}" }
    val encoders = enum.values.map { v => s"case `${v.name}` => pbos.writeEnumNoTag(${v.number})" }
    val sizers = enum.values.map { v => s"case `${v.name}` => ${PBOS}.computeEnumSizeNoTag(${v.number})" }

    s"""|${indent}object ${enum.name} extends scala.Enumeration {
        |${indent}  val ${enum.values.map(_.name).mkString(", ")} = Value
        |
        |${indent}  object codec extends ${RTPKG}.Codec[Value] {
        |${indent}    val decode: ${PBIS} => Value = { pbis =>
        |${indent}       pbis.readEnum() match {
        |${indent}        ${decoders.mkString(s"\n${indent}        ")}
        |${indent}        case v => throw new IllegalArgumentException(s"invalid enum value: $${v}")
        |${indent}      }
        |${indent}    }
        |
        |${indent}    def encode(value: Value, pbos: ${PBOS}): scala.Unit =
        |${indent}      value match {${encoders.mkString(s"\n${indent}        ", s"\n${indent}        ", s"\n${indent}      ")}}
        |
        |${indent}    def sizeOf(value: Value): scala.Int =
        |${indent}      value match {${sizers.mkString(s"\n${indent}        ", s"\n${indent}        ", s"\n${indent}      ")}}
        |${indent}  }
        |${indent}}
        |""".stripMargin
  }

  private[this] case class FieldArg(
    name: String,
    typeName: String,
    boxedTypeName: String,
    boxedDefault: String,
    proto: Either[ProtoFile.Field, ProtoFile.Oneof]
  )

  /**
   * Generate the scala representing a protobuf message.
   *
   * Messages are represented as a case class and a companion object.
   * The companion object includes a Codec and nested types.
   *
   * `oneof` types are encoded as ADTs (so that, in Scala, only one
   * field may be satisfied by the oneof).
   */
  private[this] def genMessageType(
    msgType: ProtoFile.MessageType,
    translateType: String => String,
    scope0: String,
    indent: String
  ): String = {
    val msgTypeName = upperHead(msgType.name)
    val scope = s"${scope0}.`${msgTypeName}`"

    val mapMessages = msgType.messages.filter(_.isMap)

    // First off, let's figure out the list of fields that will be
    // present in the case class. We have to merge oneofs and fields
    // to accomplish this (since we only have one slot to represent
    // all fields in the oneof):
    val fieldArgs = msgType.fields.map {
      case f =>
        val name = snakeToLowerCamel(f.name)
        f.typeRef match {
          case ProtoFile.TypeRef.Message(msg) if f.isMap =>
            // find the nested message that represents entries in the map
            // we expect this message to have "key" and "value" fields
            mapMessages.collectFirst {
              case mp if msg.endsWith(mp.name) =>
                val key = mapEntryType(mp, "key", translateType)
                val value = mapEntryType(mp, "value", translateType)
                val typ = s"Map[${key}, ${value}]"
                FieldArg(name, typ, typ, s"${typ}()", Left(f))
            }.getOrElse(throw new IllegalArgumentException(s"expected ${name} to have nested map entry message type"))
          case _ =>
            val typ = genFieldType(f, translateType)
            val boxed = if (f.isRepeated) s"Seq[${typ}]" else s"Option[${typ}]"
            val default = if (f.isRepeated) "Nil" else "None"
            FieldArg(name, typ, boxed, default, Left(f))
        }
    }

    val oneofArgs = msgType.oneofs.toSeq.map {
      case (_, o) =>
        val name = snakeToLowerCamel(o.name)
        val typ = s"${msgTypeName}.Oneof${snakeToUpperCamel(o.name)}"
        val boxed = s"Option[${typ}]"
        FieldArg(name, typ, boxed, "None", Right(o))
    }

    // Arrange all fields in numerical order.
    val args = (fieldArgs ++ oneofArgs).sortBy {
      case FieldArg(_, _, _, _, Left(f)) => f.number
      case FieldArg(_, _, _, _, Right(o)) => o.fields.map(_.number).min
    }

    val typedArgsTxt =
      args.map { case FieldArg(n, _, t, d, _) => s"`${n}`: ${t} = ${d}" } match {
        case Nil => ""
        case args => args.mkString(s"\n${indent}  ", s",\n${indent}  ", s"\n${indent}")
      }

    s"""|${indent}case class ${msgType.name}(${typedArgsTxt})
        |
        |${indent}object ${msgType.name} {
        |${genNestedTypes(msgType, translateType, scope, s"${indent}  ")}
        |${genMessageCodec(msgType, args, translateType, s"${indent}  ")}
        |${indent}}
        |""".stripMargin
  }

  private[this] def mapEntryType(mp: ProtoFile.MessageType, fieldName: String, translateType: String => String): String =
    mp.fields.collectFirst {
      case f if f.name.equals(fieldName) => genFieldType(f, translateType)
    }.getOrElse("String")

  private[this] def genNestedTypes(
    msgType: ProtoFile.MessageType,
    translateType: String => String,
    scope: String,
    indent: String
  ): String = {
    val enums = msgType.enums match {
      case Nil => ""
      case enums => enums.map(genEnum(_, indent)).mkString("\n", "", "")
    }

    val types = msgType.messages match {
      case Nil => ""
      case types => types.map(genMessageType(_, translateType, scope, indent)).mkString("\n", "", "")
    }

    val oneofs = msgType.oneofs match {
      case os if os.isEmpty => ""
      case os => os.values.map(genOneof(_, translateType, indent)).mkString("\n", "\n", "")
    }

    s"""${enums}${types}${oneofs}"""
  }

  private[this] def genMessageCodec(
    msgType: ProtoFile.MessageType,
    args: Seq[FieldArg],
    translateType: String => String,
    indent: String
  ): String = {
    s"""|${indent}object codec extends ${RTPKG}.Codec[${msgType.name}] {
        |${genMessageDecode(msgType, args, translateType, s"${indent}  ")}
        |${genMessageEncode(msgType, args, translateType, s"${indent}  ")}
        |${genMessageSizeOf(msgType, args, translateType, s"${indent}  ")}
        |${indent}}
        |""".stripMargin
  }

  private[this] def genMessageDecode(
    msgType: ProtoFile.MessageType,
    args: Seq[FieldArg],
    translateType: String => String,
    indent: String
  ): String = {
    def genReader(f: ProtoFile.Field): String = f.typeRef match {
      case ProtoFile.TypeRef.Enum(enum) => s"${translateType(enum)}.codec.decode(pbis)"
      case ProtoFile.TypeRef.Message(msg) => s"${translateType(msg)}.codec.decodeEmbedded(pbis)"
      case ProtoFile.TypeRef.Simple(TYPE_BYTES) => s"${BUF}.ByteBuffer.Owned(pbis.readByteBuffer())"
      case ProtoFile.TypeRef.Simple(typ) => s"pbis.read${simpleTypeName(typ)}()"
    }

    // The decoder starts with a bunch of vars initialized to default
    // values.
    val varDefs = args.map {
      case FieldArg(name, _, boxed, default, _) =>
        s"var ${name}Arg: $boxed = $default"
    }
    val varNames = args.map(a => s"${a.name}Arg")
    val varDecoders = args.flatMap {
      case FieldArg(name, _, _, _, Left(f)) =>
        val wireType = genWireType(f)
        val reader = genReader(f)
        val readArg =
          if (f.isMap) {
            val ref = f.typeRef match {
              case TypeRef.Message(msg) => translateType(msg)
              case _ => throw new IllegalArgumentException("expected message")
            }
            //convert reader's tuple value into message entries
            s"""|${name}Arg ++ ${ref}.unapply(${reader}).flatMap {
                |${indent}    case (Some(a), Some(b)) => Some((a,b))
                |${indent}    case _ => None
                |${indent}  }""".stripMargin
          } else if (f.isRepeated) s"${name}Arg :+ ${reader}"
          else s"Option(${reader})"

        s"""|${indent}      case ${f.number} => // ${name}: ${f.typeRef}
            |${indent}        typ match {
            |${indent}          case ${wireType} =>
            |${indent}            ${name}Arg = ${readArg}
            |${indent}          case typ =>
            |${indent}            throw new IllegalArgumentException(s"${name} expected ${wireType} got $${typ}")
            |${indent}        }
            |""".stripMargin :: Nil

      case FieldArg(name, typ, _, _, Right(o)) =>
        o.fields.map { f =>
          val ftyp = s"${typ}.`${snakeToUpperCamel(f.name)}`"
          val wireType = genWireType(f)
          val reader = genReader(f)
          val readArg = f match {
            case f if f.isRepeated =>
              s"""|${indent}            ${name}Arg match {
                  |${indent}              case None =>
                  |${indent}                val v = ${reader}
                  |${indent}                ${name}Arg = Some(${ftyp}(Seq(v)))
                  |${indent}              case Some(${ftyp}(seq)) =>
                  |${indent}                val v = ${reader}
                  |${indent}                ${name}Arg = Some(${ftyp}(seq :+ v))
                  |${indent}              case Some(_) =>
                  |${indent}                throw new IllegalArgumentException(s"more than one field provided for ${name}")
                  |${indent}            }""".stripMargin

            case f =>
              s"""|${indent}            ${name}Arg match {
                  |${indent}              case None =>
                  |${indent}                val v = ${reader}
                  |${indent}                ${name}Arg = Some(${ftyp}(v))
                  |${indent}              case Some(_) =>
                  |${indent}                throw new IllegalArgumentException(s"more than one field provided for ${name}")
                  |${indent}            }""".stripMargin
          }

          s"""|${indent}      case ${f.number} => // ${f.name}: ${f.typeRef} (oneof ${name})
              |${indent}        typ match {
              |${indent}          case ${wireType} =>
              |${readArg}
              |${indent}          case typ =>
              |${indent}            throw new IllegalArgumentException(s"${f.name} expected ${wireType} got $${typ}")
              |${indent}        }
              |""".stripMargin
        }
    }

    val varDecodersTxt = varDecoders match {
      case Nil => ""
      case decoders => decoders.mkString("\n", "", "")
    }

    s"""|${indent}override val decode: ${PBIS} => ${msgType.name} = { pbis =>
        |${indent}  ${varDefs.mkString(s"\n$indent  ")}
        |${indent}  while (!pbis.isAtEnd) {
        |${indent}    val tag = pbis.readTag()
        |${indent}    val typ = ${WFMT}.getTagWireType(tag)
        |${indent}    val num = ${WFMT}.getTagFieldNumber(tag)
        |${indent}    num match {${varDecodersTxt}
        |${indent}      case _ => // ignore unknown fields
        |${indent}    }
        |${indent}  }
        |${indent}  ${msgType.name}(${varNames.mkString(", ")})
        |${indent}}
        |""".stripMargin
  }

  private[this] def genWireType(f: ProtoFile.Field): String = f.typeRef match {
    case ProtoFile.TypeRef.Enum(_) => s"${WIRET}_VARINT"
    case ProtoFile.TypeRef.Message(_) => s"${WIRET}_LENGTH_DELIMITED"
    case ProtoFile.TypeRef.Simple(typ) => typ match {
      case TYPE_INT32 | TYPE_INT64 |
        TYPE_UINT32 | TYPE_UINT64 |
        TYPE_SINT32 | TYPE_SINT64 |
        TYPE_BOOL => s"${WIRET}_VARINT"
      case TYPE_FIXED32 | TYPE_SFIXED32 | TYPE_FLOAT => s"${WIRET}_FIXED32"
      case TYPE_FIXED64 | TYPE_SFIXED64 | TYPE_DOUBLE => s"${WIRET}_FIXED64"
      case TYPE_BYTES | TYPE_STRING => s"${WIRET}_LENGTH_DELIMITED"
      case typ => throw new IllegalArgumentException(s"field ${f.name}: ${typ} not supported")
    }
  }

  private[this] def genMessageEncode(
    msgType: ProtoFile.MessageType,
    args: Seq[FieldArg],
    translateType: String => String,
    indent: String
  ): String = {
    val encoders = args.map {
      case FieldArg(name, typ, _, _, Left(f)) if f.isRepeated =>
        val writer =
          //when iterating over maps, convert tuple value into entry types
          if (f.isMap) f.typeRef match {
            case ProtoFile.TypeRef.Message(msg) =>
              genWriteKind(f, translateType, s"${translateType(msg)}(Some(value._1), Some(value._2))", s"${indent}    ")
            case _ => throw new IllegalArgumentException("excepted typeRef of type Message")
          }
          else genWriteKind(f, translateType, "value", s"${indent}    ")

        s"""|${indent}  val ${name}Iter = msg.`${name}`.iterator
            |${indent}  while (${name}Iter.hasNext) {
            |${indent}    val value = ${name}Iter.next()
            |${writer}
            |${indent}  }
            |""".stripMargin

      case FieldArg(name, typ, _, _, Left(f)) =>
        val writer = genWriteKind(f, translateType, "value", s"${indent}      ")
        s"""|${indent}  msg.`${name}` match {
            |${indent}    case None =>
            |${indent}    case Some(value) =>
            |${writer}
            |${indent}  }
            |""".stripMargin

      case FieldArg(name, typ, _, _, Right(o)) =>
        val fieldWriters = o.fields.map { f =>
          val ftyp = s"${typ}.`${snakeToUpperCamel(f.name)}`"
          val writer = genWriteKind(f, translateType, "value", s"${indent}      ")
          s"""|${indent}    case Some(${ftyp}(value)) =>
              |${writer}""".stripMargin
        }

        s"""|${indent}  msg.`${name}` match {
            |${indent}    case None =>
            |${fieldWriters.mkString("\n")}
            |${indent}  }
            |""".stripMargin
    }

    s"""|${indent}override def encode(msg: ${msgType.name}, pbos: ${PBOS}): scala.Unit = {
        |${encoders.mkString("", "\n", indent)}}
        |""".stripMargin
  }

  private[this] def genMessageSizeOf(
    msgType: ProtoFile.MessageType,
    args: Seq[FieldArg],
    translateType: String => String,
    indent: String
  ): String = {
    val sizeAdditions = args.map {
      case FieldArg(name, typ, _, _, Left(f)) if f.isMap =>
        val sizeOf = f.typeRef match {
          case ProtoFile.TypeRef.Message(msg) =>
            genComputeSizeTagged(f, s"${translateType(msg)}(Some(value._1), Some(value._2))", translateType)
          case _ => ""
        }
        s"""|${indent}  val ${name}Iter = msg.`${name}`.iterator
            |${indent}  while (${name}Iter.hasNext) {
            |${indent}    val value = ${name}Iter.next()
            |${indent}    val sz = ${sizeOf}
            |${indent}    size += sz
            |${indent}  }""".stripMargin

      case FieldArg(name, typ, _, _, Left(f)) if f.isRepeated =>
        val sizeOf = genComputeSizeTagged(f, "value", translateType)
        s"""|${indent}  val ${name}Iter = msg.`${name}`.iterator
            |${indent}  while (${name}Iter.hasNext) {
            |${indent}    val value = ${name}Iter.next()
            |${indent}    val sz = ${sizeOf}
            |${indent}    size += sz
            |${indent}  }""".stripMargin

      case FieldArg(name, typ, _, _, Left(f)) =>
        val sizeOf = genComputeSizeTagged(f, "value", translateType)
        s"""|${indent}  msg.`${name}` match {
            |${indent}    case None =>
            |${indent}    case Some(value) =>
            |${indent}      val sz = ${sizeOf}
            |${indent}      size += sz
            |${indent}  }""".stripMargin

      case FieldArg(name, typ, _, _, Right(o)) =>
        val fieldSizes = o.fields.map { f =>
          val ftyp = s"${typ}.${snakeToUpperCamel(f.name)}"
          val sizeOf = genComputeSizeTagged(f, "value", translateType)
          s"""|${indent}    case Some(${ftyp}(value)) =>
              |${indent}      val sz = ${sizeOf}
              |${indent}      size += sz
              |""".stripMargin
        }

        s"""|${indent}  msg.`${name}` match {
            |${indent}    case None =>
            |${fieldSizes.mkString("")}
            |${indent}  }
            |""".stripMargin
    }

    s"""|${indent}override def sizeOf(msg: ${msgType.name}): scala.Int = {
        |${indent}  var size = 0
        |${sizeAdditions.mkString("", "\n", "")}
        |${indent}  size
        |${indent}}
        |""".stripMargin
  }

  private[this] def genOneof(
    oneof: ProtoFile.Oneof,
    translateType: String => String,
    indent: String
  ): String = {
    val parentType = snakeToUpperCamel(oneof.name)
    val fieldTypes = oneof.fields.map { f =>
      val fieldType = snakeToUpperCamel(f.name)
      val valueType = genFieldType(f, translateType)
      s"case class ${fieldType}(value: ${valueType}) extends Oneof${parentType}"
    }
    s"""|${indent}sealed trait Oneof${parentType}
        |${indent}object Oneof${parentType} {
        |${indent}  ${fieldTypes.mkString(s"\n$indent  ")}
        |${indent}}""".stripMargin
  }

  private[this] def simpleTypeName(typ: FieldDescriptorProto.Type): String = typ match {
    case TYPE_BOOL => "Bool"
    case TYPE_STRING => "String"
    case TYPE_INT32 => "Int32"
    case TYPE_INT64 => "Int64"
    case TYPE_UINT32 => "UInt32"
    case TYPE_UINT64 => "UInt64"
    case TYPE_SINT32 => "SInt32"
    case TYPE_SINT64 => "SInt64"
    case TYPE_FIXED32 => "Fixed32"
    case TYPE_FIXED64 => "Fixed64"
    case TYPE_SFIXED32 => "Sfixed32"
    case TYPE_SFIXED64 => "Sfixed64"
    case TYPE_FLOAT => "Float"
    case TYPE_DOUBLE => "Double"
    case typ => throw new IllegalArgumentException(s"unsupported typ: ${typ}")
  }

  private[this] def genWriteKind(
    f: ProtoFile.Field,
    translateType: String => String,
    valueName: String,
    indent: String
  ): String = f.typeRef match {
    case ProtoFile.TypeRef.Enum(enum) =>
      val typ = translateType(enum)
      s"""|${indent}pbos.writeTag(${f.number}, ${WIRET}_VARINT)
          |${indent}${typ}.codec.encode(${valueName}, pbos)""".stripMargin

    case ProtoFile.TypeRef.Message(msgt) if f.isMap =>
      val typ = translateType(msgt)
      s"""|${indent}pbos.writeTag(${f.number}, ${WIRET}_LENGTH_DELIMITED)
          |${indent}${typ}.codec.encodeEmbedded(${valueName}, pbos)""".stripMargin

    case ProtoFile.TypeRef.Message(msgt) =>
      val typ = translateType(msgt)
      s"""|${indent}pbos.writeTag(${f.number}, ${WIRET}_LENGTH_DELIMITED)
          |${indent}${typ}.codec.encodeEmbedded(${valueName}, pbos)""".stripMargin

    case ProtoFile.TypeRef.Simple(TYPE_BYTES) =>
      s"""|${indent}val bb = ${BUF}.ByteBuffer.Owned.extract(${valueName})
          |${indent}pbos.writeByteBuffer(${f.number}, bb.slice())""".stripMargin

    case ProtoFile.TypeRef.Simple(wtyp) =>
      val typ = simpleTypeName(wtyp)
      s"""${indent}pbos.write${typ}(${f.number}, ${valueName})"""
  }

  private[this] def genComputeSize(
    f: ProtoFile.Field,
    name: String,
    translateType: String => String
  ): String = f.typeRef match {
    case ProtoFile.TypeRef.Enum(typ) =>
      s"${translateType(typ)}.codec.sizeOf(${name})"
    case ProtoFile.TypeRef.Message(typ) if f.isMap =>
      s"${translateType(typ)}.codec.sizeOfEmbedded(${name})"
    case ProtoFile.TypeRef.Message(typ) =>
      s"${translateType(typ)}.codec.sizeOfEmbedded(${name})"
    case ProtoFile.TypeRef.Simple(TYPE_BYTES) =>
      s"${PBOS}.computeUInt32SizeNoTag(${name}.length) + ${name}.length"
    case ProtoFile.TypeRef.Simple(typ) =>
      s"${PBOS}.compute${simpleTypeName(typ)}SizeNoTag(${name})"
  }

  private[this] def genComputeSizeTagged(
    f: ProtoFile.Field,
    name: String,
    translateType: String => String
  ): String =
    s"${PBOS}.computeTagSize(${f.number}) + ${genComputeSize(f, name, translateType)}"

  private[this] def genService(
    svc: ProtoFile.Service,
    translateType: String => String,
    protoPkg: String,
    scope: String,
    indent: String
  ): String = {
    val svcName = s"""${protoPkg.stripPrefix(".")}.${svc.name}"""
    val methods = svc.rpcs.map { rpc =>
      val method = lowerHead(rpc.name)
      val reqT = translateType(rpc.client.kind)
      val rspT = translateType(rpc.server.kind)
      val (boxedReqT, boxedRspT) = (rpc.client, rpc.server) match {
        case (ProtoFile.Io.Unary(_), ProtoFile.Io.Unary(_)) =>
          (reqT, s"com.twitter.util.Future[$rspT]")
        case (ProtoFile.Io.Unary(_), ProtoFile.Io.Stream(_)) =>
          (reqT, s"${RTPKG}.Stream[$rspT]")
        case (ProtoFile.Io.Stream(_), ProtoFile.Io.Unary(_)) =>
          (s"${RTPKG}.Stream[$reqT]", s"com.twitter.util.Future[$rspT]")
        case (ProtoFile.Io.Stream(_), ProtoFile.Io.Stream(_)) =>
          (s"${RTPKG}.Stream[$reqT]", s"${RTPKG}.Stream[$rspT]")
      }
      s"def ${method}(req: ${boxedReqT}): ${boxedRspT}"
    }
    val methodsTxt = methods.mkString(s"\n${indent}  ", s"\n${indent}  ", s"\n${indent}")

    val clientMethods = svc.rpcs.map(genClientMethod(svcName, _, translateType, s"${indent}    "))

    val srvRpcs = svc.rpcs.map(genServiceRpc(_, translateType, s"${indent}      "))
    val srvRpcsTxt = srvRpcs match {
      case Nil => ""
      case rpcs => rpcs.mkString("\n", ",\n", s"\n${indent}    ")
    }

    s"""|${indent}trait ${svc.name} {${methodsTxt}}
        |
        |${indent}object ${svc.name} {
        |${indent}  class Client(
        |${indent}    client: com.twitter.finagle.Service[${H2PKG}.Request, ${H2PKG}.Response]
        |${indent}  ) extends ${svc.name} {
        |${clientMethods.mkString("\n")}
        |${indent}  }
        |
        |${indent}  object Server {
        |${indent}    val name = "${svcName}"
        |
        |${indent}    def apply(iface: ${scope}.${svc.name}): Server =
        |${indent}      new Server(iface)
        |${indent}  }
        |
        |${indent}  class Server(iface: ${scope}.${svc.name})
        |${indent}      extends ${RTPKG}.ServerDispatcher.Service {
        |${indent}    override val name = Server.name
        |${indent}    override val rpcs = Seq[$RTPKG.ServerDispatcher.Rpc](${srvRpcsTxt})
        |${indent}  }
        |${indent}}
        |""".stripMargin
  }

  private[this] def genServiceRpc(
    rpc: ProtoFile.Rpc,
    translateType: String => String,
    indent: String
  ): String = {
    val method = lowerHead(rpc.name)
    val rpcT = s"${upperHead(rpc.name)}Rpc"
    val in = rpc.client.kind
    val out = rpc.server.kind
    val inT = translateType(in)
    val outT = translateType(out)
    val rpcType = (rpc.client, rpc.server) match {
      case (ProtoFile.Io.Unary(_), ProtoFile.Io.Unary(_)) => "UnaryToUnary"
      case (ProtoFile.Io.Unary(_), ProtoFile.Io.Stream(_)) => "UnaryToStream"
      case (ProtoFile.Io.Stream(_), ProtoFile.Io.Unary(_)) => "StreamToUnary"
      case (ProtoFile.Io.Stream(_), ProtoFile.Io.Stream(_)) => "StreamToStream"
    }
    s"""|${indent}new ${RTPKG}.ServerDispatcher.Rpc.${rpcType}(
        |${indent}  "${rpc.name}",
        |${indent}  iface.${method} _,
        |${indent}  ${inT}.codec,
        |${indent}  ${outT}.codec
        |${indent})"""
  }

  def genClientMethod(
    svcName: String,
    rpc: ProtoFile.Rpc,
    translateType: String => String,
    indent: String
  ): String = {
    val method = lowerHead(rpc.name)
    val path = s"/${svcName}/${rpc.name}"
    val reqT = translateType(rpc.client.kind)
    val rspT = translateType(rpc.server.kind)

    (rpc.client, rpc.server) match {
      case (ProtoFile.Io.Unary(_), ProtoFile.Io.Unary(_)) =>
        s"""|${indent}private[this] val `__${method}Rpc` =
            |${indent}  ${RTPKG}.ClientDispatcher.Rpc.UnaryToUnary(
            |${indent}    client, "${path}",
            |${indent}    ${reqT}.codec,
            |${indent}    ${rspT}.codec
            |${indent}  )
            |${indent}override def ${method}(msg: $reqT): com.twitter.util.Future[$rspT] =
            |${indent}  __${method}Rpc(msg)
            |""".stripMargin

      case (ProtoFile.Io.Unary(_), ProtoFile.Io.Stream(_)) =>
        s"""|${indent}private[this] val __${method}Rpc =
            |${indent}  ${RTPKG}.ClientDispatcher.Rpc.UnaryToStream(
            |${indent}    client, "${path}",
            |${indent}    ${reqT}.codec,
            |${indent}    ${rspT}.codec
            |${indent}  )
            |${indent}override def `${method}`(msg: $reqT): ${RTPKG}.Stream[$rspT] =
            |${indent}  __${method}Rpc(msg)
            |""".stripMargin

      case (ProtoFile.Io.Stream(_), ProtoFile.Io.Unary(_)) =>
        s"""|${indent}private[this] val `__${method}Rpc` =
            |${indent}  ${RTPKG}.ClientDispatcher.Rpc.StreamToUnary(
            |${indent}    client, "${path}",
            |${indent}    ${reqT}.codec,
            |${indent}    ${rspT}.codec
            |${indent}  )
            |${indent}override def `${method}`(msgs: ${RTPKG}.Stream[$reqT]): com.twitter.util.Future[$rspT] =
            |${indent}  __${method}Rpc(msgs)
            |""".stripMargin

      case (ProtoFile.Io.Stream(_), ProtoFile.Io.Stream(_)) =>
        s"""|${indent}private[this] val `__${method}Rpc` =
            |${indent}  ${RTPKG}.ClientDispatcher.Rpc.StreamToStream(
            |${indent}    client, "${path}",
            |${indent}    ${reqT}.codec,
            |${indent}    ${rspT}.codec
            |${indent}  )
            |${indent}override def `${method}`(msgs:  ${RTPKG}.Stream[$reqT]): ${RTPKG}.Stream[$rspT] =
            |${indent}  __${method}Rpc(msgs)
            |""".stripMargin
    }
  }

  def genFieldType(f: ProtoFile.Field, translate: String => String): String =
    f.typeRef match {
      case ProtoFile.TypeRef.Enum(enum) => s"${translate(enum)}.Value"
      case ProtoFile.TypeRef.Message(msg) => translate(msg)
      case ProtoFile.TypeRef.Simple(typ) => typ match {
        case TYPE_BYTES => BUF
        case TYPE_BOOL => "Boolean"
        case TYPE_DOUBLE => "Double"
        case TYPE_FLOAT => "Float"
        case TYPE_STRING => "String"
        case TYPE_INT32 | TYPE_SINT32 | TYPE_UINT32 | TYPE_FIXED32 | TYPE_SFIXED32 => "Int"
        case TYPE_INT64 | TYPE_SINT64 | TYPE_UINT64 | TYPE_FIXED64 | TYPE_SFIXED64 => "Long"
        case typ => throw new IllegalArgumentException(s"unsupported type: ${typ}")
      }
    }

  def genBoxedFieldType(f: ProtoFile.Field, translate: String => String): String = {
    val typ = genFieldType(f, translate)
    if (f.isRepeated) s"Seq[$typ]"
    else s"Option[$typ]"
  }

  private[this] val SnakeRE = """_(.)""".r
  private[this] val HeadRE = """^(.)""".r

  private[this] def snakeToCamel(in: String): String =
    SnakeRE.replaceAllIn(in, m => upperHead(m.group(1)))

  private[this] def lowerHead(in: String): String =
    HeadRE.replaceAllIn(in, _.group(1).toLowerCase)

  private[this] def upperHead(in: String): String =
    HeadRE.replaceAllIn(in, _.group(1).toUpperCase)

  private[this] def snakeToLowerCamel(in: String): String =
    lowerHead(snakeToCamel(in))

  private[this] def snakeToUpperCamel(in: String): String =
    upperHead(snakeToCamel(in))
}
