package io.buoyant.router.context.h2

import com.twitter.finagle.buoyant.h2.param
import io.buoyant.router.context.LocalKey

/**
 * We set the StreamClassifier from the path stack into a local context
 * so that when the request enters the client stack, it can use the path stack's
 * stream classifier for reporting stats.
 *
 * This is based directly on `ResponseClassifierCtx` in the H1 protocol
 */
object StreamClassifierCtx extends LocalKey[param.H2StreamClassifier]("H2StreamClassifier")
