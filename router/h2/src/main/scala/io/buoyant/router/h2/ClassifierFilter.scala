package io.buoyant.router.h2

import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.{param => _, _}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Throw, Try}

object ClassifierFilter {
  val role = Stack.Role("Classifier")

  val SuccessClassHeader = "l5d-success-class"
  val log = Logger.get("H2ClassifierFilter")

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.H2Classifier, ServiceFactory[Request, Response]] {
      override val role: Stack.Role = ClassifierFilter.role
      override val description = "Sets the stream classification into a header"
      override def make(
        classifierP: param.H2Classifier,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = {
        val param.H2Classifier(classifier) = classifierP
        new ClassifierFilter(classifier).andThen(next)
      }
    }

  private[this] object ResponseSuccessClass {
    @inline def unapply(message: Message): Option[ResponseClass] =
      message.headers.get(SuccessClassHeader).map { value =>
        val success = Try { value.toDouble }.getOrElse {
          log.warning(s"invalid `l5d-success-class` value $value, assumed failure")
          0.0
        }
        if (success > 0.0) ResponseClass.Successful(success)
        else ResponseClass.Failed(false)
      }
  }

  object SuccessClassClassifier extends H2Classifier {
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(ResponseSuccessClass(c)))))) => c
    }

    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(_, Return(ResponseSuccessClass(c))) => c
    }
  }
}

class ClassifierFilter(classifier: H2Classifier) extends SimpleFilter[Request, Response] {
  import ClassifierFilter.SuccessClassHeader

  private[this] val successHeader: ResponseClass => String =
    _.fractionalSuccess.toString
  private[this] val classifyEarly: H2ReqRep => Option[String] =
    classifier.responseClassifier.lift(_).map(successHeader)
  private[this] val classifyStream: H2ReqRepFrame => String =
    successHeader.compose(classifier.streamClassifier(_))

  def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {
    svc(req).map { rep: Response =>
      classifyEarly(H2ReqRep(req, rep))
        .map { success =>
          // classify early - response class goes in headers
          rep.headers.set(SuccessClassHeader, success)
          rep
        }
        .getOrElse {
          // if the early classification attempt is not defined, attempt
          // late classification on the last frame in the response stream
          val stream = rep.stream.flatMap {
            case frame: Trailers =>
              // if the final frame is a Trailers frame, just add the
              // success class header to it
              val success = classifyStream(H2ReqRepFrame(req, Return(rep), Some(Return(frame))))
              frame.set(SuccessClassHeader, success)
              frame.set("te", "trailers")
              Seq(frame)
            case frame if frame.isEnd =>
              // if the final frame is a Return, but not a Trailers,
              // then we need to send the final frame followed by a new
              // Trailers frame
              val success = classifyStream(H2ReqRepFrame(req, Return(rep), Some(Return(frame))))
              Seq(frame, Trailers(SuccessClassHeader -> success))
            case frame => Seq(frame)
          }
          Response(rep.headers, stream)
        }
    }
  }
}
