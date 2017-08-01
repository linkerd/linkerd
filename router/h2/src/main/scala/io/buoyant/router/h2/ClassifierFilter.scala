package io.buoyant.router.h2

import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, param}
import com.twitter.finagle.buoyant.h2.service.H2ReqRepFrame.FinalFrame
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.util.{Future, Return, Try}

object ClassifierFilter {
  val role = Stack.Role("Classifier")

  val SuccessClassHeader = "l5d-success-class"

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
    @inline def unapply(headers: Headers): Option[ResponseClass] =
      headers.get(SuccessClassHeader).map { value =>
        val success = Try(value.toDouble).toOption.getOrElse(0.0)
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

  private[this] def successClass[T](f: PartialFunction[T, ResponseClass])(r: T) =
    f.lift(r).map(_.fractionalSuccess).getOrElse(0.0).toString

  private[this] val responseSuccessClass =
    successClass(classifier.responseClassifier)(_)

  private[this] val streamSuccessClass =
    successClass(classifier.streamClassifier)(_)

  def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {
    svc(req).map { rep: Response =>
      if (rep.stream.isEmpty) {
        // classify early - response class goes in headers
        val success = responseSuccessClass(H2ReqRep(req, Return(rep)))
        rep.headers.set(SuccessClassHeader, success)
        rep
      } else {
        // if the early classification attempt is not defined, attempt
        // late classification on the last frame in the response stream
        val stream = rep.stream.onFrame {
          case Return(frame: Trailers) =>
            val success = streamSuccessClass(H2ReqRepFrame(req, Return(rep), Some(Return(frame))))
            frame.set(SuccessClassHeader, success.toString)
          case _ =>
        }
        Response(rep.headers, stream)
      }
    }
  }
}
