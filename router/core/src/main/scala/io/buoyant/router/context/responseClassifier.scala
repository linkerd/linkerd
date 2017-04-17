package io.buoyant.router.context

import com.twitter.finagle.param

/**
 * We set the ResponseClassifier from the path stack into a local context
 * so that when the request enters the client stack, it can use the path stack's
 * response classifier for reporting stats
 */
object ResponseClassifierCtx extends LocalKey[param.ResponseClassifier]("ResponseClassifier")
