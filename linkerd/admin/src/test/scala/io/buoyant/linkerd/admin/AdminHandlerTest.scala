package io.buoyant.linkerd.admin

import org.scalatest.FunSuite

class AdminHandlerTest extends FunSuite {
  test("handles empty content") {
    assert(AdminHandler.html("").contains("linkerd admin"))
  }

  test("handles populated params") {
    val content = "fake content"
    val tailContent = "fake tail content"
    val csses = Seq("foo.css", "bar.css")

    val html = AdminHandler.html(
      content = content,
      tailContent = tailContent,
      csses = csses
    )

    assert(html.contains(content))
    assert(html.contains(tailContent))
    csses.foreach { css =>
      assert(html.contains(s"""css/$css"""))
    }
  }
}
