package io.buoyant.router.context.h2

import com.twitter.finagle.buoyant.h2.param
import io.buoyant.router.context.LocalKey

/**
 * We set the H2Classifier from the path stack into a local context
 * so that when the request enters the client stack, it can use the path stack's
 * stream classifier for reporting stats.
 *
 * This is based directly on `ResponseClassifierCtx` in the H1 protocol
 */
object H2ClassifierCtx extends LocalKey[param.H2Classifier]("H2Classifier")
