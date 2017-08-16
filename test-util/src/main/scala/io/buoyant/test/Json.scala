package io.buoyant.test

import com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.scalatest.exceptions.TestFailedException

import scala.util.control.NonFatal

trait Json { _: Logging =>

  private val mapper = new ObjectMapper with ScalaObjectMapper
  private lazy val sortingMapper = (new ObjectMapper with ScalaObjectMapper).configure(ORDER_MAP_ENTRIES_BY_KEYS, true)

  def assertJsonEquals(actualJson: String, expectedJson: String): Unit = {
    val actualJsonNode = tryJsonNodeParse(actualJson)
    val expectedJsonNode = tryJsonNodeParse(expectedJson)
    if (actualJsonNode != expectedJsonNode) {
      val message = mkMessage(expectedJsonNode, actualJsonNode)
      throw new TestFailedException(s"Json diff failed: \n$message", 1)
    }
  }

  private def tryJsonNodeParse(jsonStr: String): JsonNode = {
    try {
      mapper.readValue[JsonNode](jsonStr)
    } catch {
      case NonFatal(e) =>
        log.warning(e.getMessage)
        new TextNode(jsonStr)
    }
  }

  private def mkMessage(expected: JsonNode, actual: JsonNode): String = {
    val expectedJsonSorted = sortedString(expected)
    val actualJsonSorted = sortedString(actual)

    val expectedHeader = "Expected: "
    val diffStartIdx = indexOfDifference(actualJsonSorted, expectedJsonSorted)

    val message = new StringBuilder
    message.append(" " * (expectedHeader.length + diffStartIdx) + "*\n")
    message.append(s"Actual:   $actualJsonSorted\n")
    message.append(expectedHeader + expectedJsonSorted)
    message.toString()
  }

  private def sortedString(jsonNode: JsonNode): String = {
    if (jsonNode.isTextual) {
      jsonNode.textValue()
    } else {
      val node = sortingMapper.treeToValue(jsonNode, classOf[Object])
      sortingMapper.writeValueAsString(node)
    }
  }

  private def indexOfDifference(str1: String, str2: String): Int = {
    def diff(i: Int): Int = {
      if (i == str1.length || i == str2.length || str1.charAt(i) != str2.charAt(i)) {
        if (i < str1.length || i < str2.length) i else -1
      } else diff(i + 1)
    }
    diff(0)
  }
}
