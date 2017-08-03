package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.h2.service.H2Classifiers
import com.twitter.finagle.tracing.{DefaultTracer, Tracer => FTracer}
import com.twitter.util.StorageUnit

package object param {

  case class ClientPriorKnowledge(assumed: Boolean)
  implicit object ClientPriorKnowledge extends Stack.Param[ClientPriorKnowledge] {
    val default = ClientPriorKnowledge(true)
  }

  object FlowControl {

    /**
     * Controls whether connection windows are automatically updated.
     *
     * When enabled, connection-level flow control is effectively
     * disabled (i.e. constrained by SETTINGS_MAX_CONCURRENT_STREAMS *
     * SETTINGS_INITIAL_WINDOW_SIZE).
     */
    case class AutoRefillConnectionWindow(enabled: Boolean)
    implicit object AutoRefillConnectionWindow extends Stack.Param[AutoRefillConnectionWindow] {
      /**
       * By default, the connection window is ignored.
       *
       * This should be changed on resolution of
       * https://github.com/linkerd/linkerd/issues/1044
       */
      val default = AutoRefillConnectionWindow(true)
    }

    /**
     * Controls how frequently WINDOW_UPDATE messages are sent to the remote.
     */
    case class WindowUpdateRatio(ratio: Float) {
      require(0.0 < ratio && ratio < 1.0, "ratio must be on (0.0, 1.0), exclusive")
    }
    implicit object WinodwUpdateRatio extends Stack.Param[WindowUpdateRatio] {
      val default = WindowUpdateRatio(0.99f)
    }
  }

  object Settings {

    /**
     *    SETTINGS_HEADER_TABLE_SIZE (0x1): Allows the sender to
     *       inform the remote endpoint of the maximum size of the
     *       header compression table used to decode header blocks, in
     *       octets.  The encoder can select any size equal to or less
     *       than this value by using signaling specific to the header
     *       compression format inside a header block.  The initial
     *       value is 4,096 octets.
     */
    case class HeaderTableSize(size: Option[StorageUnit])
    implicit object HeaderTableSize extends Stack.Param[HeaderTableSize] {
      val default = HeaderTableSize(None)
    }

    /**
     *    SETTINGS_INITIAL_WINDOW_SIZE (0x4):  Indicates the sender's initial
     *       window size (in octets) for stream-level flow control.  The
     *       initial value is 2^16-1 (65,535) octets.
     *
     *       This setting affects the window size of all streams (see
     *       Section 6.9.2).
     *
     *       Values above the maximum flow-control window size of 2^31-1 MUST
     *       be treated as a connection error (Section 5.4.1) of type
     *       FLOW_CONTROL_ERROR.
     */
    case class InitialStreamWindowSize(size: Option[StorageUnit])
    implicit object InitialStreamWindowSize extends Stack.Param[InitialStreamWindowSize] {
      val default = InitialStreamWindowSize(None)
    }

    // TODO case class InitialConnectionWindowSize(size: Option[StorageUnit])

    /**
     *    SETTINGS_MAX_CONCURRENT_STREAMS (0x3):  Indicates the maximum number
     *       of concurrent streams that the sender will allow.  This limit is
     *       directional: it applies to the number of streams that the sender
     *       permits the receiver to create.  Initially, there is no limit to
     *       this value.  It is recommended that this value be no smaller than
     *       100, so as to not unnecessarily limit parallelism.
     *
     *       A value of 0 for SETTINGS_MAX_CONCURRENT_STREAMS SHOULD NOT be
     *       treated as special by endpoints.  A zero value does prevent the
     *       creation of new streams; however, this can also happen for any
     *       limit that is exhausted with active streams.  Servers SHOULD only
     *       set a zero value for short durations; if a server does not wish to
     *       accept requests, closing the connection is more appropriate.
     */
    case class MaxConcurrentStreams(streams: Option[Long])
    implicit object MaxConcurrentStreams extends Stack.Param[MaxConcurrentStreams] {
      val default = MaxConcurrentStreams(None)
    }

    /**
     *    SETTINGS_MAX_FRAME_SIZE (0x5):  Indicates the size of the largest
     *       frame payload that the sender is willing to receive, in octets.
     *
     *       The initial value is 2^14 (16,384) octets.  The value advertised
     *       by an endpoint MUST be between this initial value and the maximum
     *       allowed frame size (2^24-1 or 16,777,215 octets), inclusive.
     *       Values outside this range MUST be treated as a connection error
     *       (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    case class MaxFrameSize(size: Option[StorageUnit])
    implicit object MaxFrameSize extends Stack.Param[MaxFrameSize] {
      val default = MaxFrameSize(None)
    }

    /**
     *    SETTINGS_MAX_HEADER_LIST_SIZE (0x6):  This advisory setting informs a
     *       peer of the maximum size of header list that the sender is
     *       prepared to accept, in octets.  The value is based on the
     *       uncompressed size of header fields, including the length of the
     *       name and value in octets plus an overhead of 32 octets for each
     *       header field.
     *
     *       For any given request, a lower limit than what is advertised MAY
     *       be enforced.  The initial value of this setting is unlimited.
     */
    case class MaxHeaderListSize(size: Option[StorageUnit])
    implicit object MaxHeaderListSize extends Stack.Param[MaxHeaderListSize] {
      val default = MaxHeaderListSize(None)
    }
  }

  /**
   * Copied from com.twitter.finagle.param.Tracer
   */
  case class Tracer(tracer: FTracer) {
    def mk(): (Tracer, Stack.Param[Tracer]) =
      (this, Tracer.param)
  }
  object Tracer {
    implicit val param: Stack.Param[Tracer] = Stack.Param(Tracer(DefaultTracer))
  }

  case class H2Classifier(classifier: service.H2Classifier) {
    def mk(): (H2Classifier, Stack.Param[H2Classifier]) =
      (this, H2Classifier.param)
  }
  object H2Classifier {
    implicit val param: Stack.Param[H2Classifier] =
      Stack.Param(H2Classifier(H2Classifiers.Default))
  }
}
