package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future

private[admin] class HelpPageHandler(adminHandler: AdminHandler) extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = adminHandler.mkResponse(html)

  val html = adminHandler.html(
    content = HelpPageHandler.helpHtml,
    navHighlight = "help"
  )
}

object HelpPageHandler {
  val helpHtml = s"""
    <div class="container main help-page">
      <div class="container main">
        <h1 class="title">Need some help?</h1>
        <div class="content">
          <p>Full documentation is available at <a href="https://linkerd.io">linkerd.io</a></p>
          <p>
            We&rsquo;d love to help you get linkerd working for your use case,
            and we&rsquo;re very interested in your feedback!</p>
          <p>
            If you&rsquo;re having trouble or think you&rsquo;ve found a bug,
            start by checking <a href="https://linkerd.io/doc/faq/">the FAQ</a>.
            If you still need help, you can reach us in a couple ways:
          </p>
          <ul>
              <li>Ask questions about configuration or troubleshooting in the <a href="https://discourse.linkerd.io/">linkerd Discourse instance</a>.</li>
              <li>Chat with us in <a href="http://slack.linkerd.io">the linkerd public Slack</a>.</li>
              <li>Email us at <a href="mailto:linkerd-users@googlegroups.com">linkerd-users@googlegroups.com</a>.</li>
          </ul>
          <p>You can also report bugs or file feature requests on the <a href="https://github.com/buoyantio/linkerd">linkerd Github repo</a>.</p>
          <h1 id="remote-diagnosis">Remote diagnosis</h1>
          <p>Diagnosing why linkerd is having trouble can be tricky. You can help us by providing a few things.</p>
          <p>First, a metrics dump is often critical for us to understanding what linkerd is doing. You can accomplish this by running the following script:</p>
          <pre><code class="language-bash">#!/bin/bash
while true; do
  curl -s http://localhost:9990/admin/metrics.json &gt; l5d_metrics_`date -u +'%s'`.json
  sleep 60;
done</code></pre>
          <p>This script will produce one file a minute.</p>
          <p>If these metrics are insufficient, we may also ask you to capture some network traffic. One way to do this is with tcpdump:</p>
          <pre><code class="language-bash">/usr/sbin/tcpdump -s 65535 'tcp port 4140' -w linkerd.pcap</code></pre>
          <p>(assuming you&rsquo;re running linkerd on the default port of 4140).</p>
          <p>Run this command while the problem is occurring and assemble the results in a tar or zip file. You can attach these files directly to the Github issue.</p>
        </div>
      </div>
    </div>
  """
}
