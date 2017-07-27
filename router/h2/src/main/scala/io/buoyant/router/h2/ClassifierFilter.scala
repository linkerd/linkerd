package io.buoyant.router.h2

import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.buoyant.h2.param
import com.twitter.finagle.buoyant.h2.service.H2ReqRep.FinalFrame
import com.twitter.finagle.buoyant.h2.service.{H2ReqRep, H2StreamClassifier}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.util.{Future, Return, Try}

object ClassifierFilter {
  val role = Stack.Role("Classifier")

  val SuccessClassHeader = "l5d-success-class"

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.H2StreamClassifier, ServiceFactory[Request, Response]] {
      val role = ClassifierFilter.role
      val description = "Sets the stream classification into a header"
      def make(classifierP: param.H2StreamClassifier, next: ServiceFactory[Request, Response]) = {
        val param.H2StreamClassifier(classifier) = classifierP
        new ClassifierFilter(classifier).andThen(next)
      }
    }

  val successClassClassifier: H2StreamClassifier = {
    case H2ReqRep(req, Return((rep: Response, frame: FinalFrame)))
      if rep.headers.contains(SuccessClassHeader) =>
      val success = rep.headers.get(SuccessClassHeader)
        .flatMap { value =>
          Try(value.toDouble).toOption
        }.getOrElse(0.0)
      if (success > 0.0)
        ResponseClass.Successful(success)
      else
        ResponseClass.Failed(false)
  }
}

class ClassifierFilter(classifier: H2StreamClassifier) extends SimpleFilter[Request, Response] {

  private[this] val successClass = classifier.andThen(_.fractionalSuccess).lift

  def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {
    svc(req).map { rep =>
      val stream1 = rep.stream.onFrame {
        case Return(f: Trailers) =>
          successClass(H2ReqRep(req, Return(rep), Some(Return(f)))) match {
            case Some(success) =>
              f.set(ClassifierFilter.SuccessClassHeader, success.toString)
            case None =>
          }
        case Return(f) if f.isEnd =>
          successClass(H2ReqRep(req, Return(rep), Some(Return(f)))) match {
            case Some(success) =>
              rep.headers.set(ClassifierFilter.SuccessClassHeader, success.toString)
            case None =>
          }
        case _ =>
      }
      Response(rep.headers, stream1)
    }
  }
}
