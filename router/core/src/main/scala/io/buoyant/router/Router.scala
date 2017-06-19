package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client._
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.service.{FailFastFactory, Retries, StatsFilter}
import com.twitter.finagle.stack.Endpoint
import com.twitter.finagle.stats.DefaultStatsReceiver
import com.twitter.util.{Future, Time}
import io.buoyant.router.context._

/**
 * A `Router` is a lot like a `com.twitter.finagle.Client`, except
 * that it is not configured with destination
 * `com.twitter.finagle.Name`.  Instead, a
 * [[com.twitter.finagle.buoyant.Dst destination]] is determined for
 * each request by an [[RoutingFactory.Identifier Identifier]] and a client is
 * then dynamically resolved.
 *
 * Routers are implemented by the various protocol packages.  For
 * example:
 *
 * {{{
 * object Http extends Router[Request, Response] ...
 *
 * val router: ServiceFactory[Request, Response] =
 *   Http.newRouter("http")
 * }}}
 */
trait Router[Req, Rsp] {

  /**
   * Create a new ServiceFactory that performs per-request routing.
   */
  def factory(): ServiceFactory[Req, Rsp]
}

/**
 * A [[Router]] that composes three `com.twitter.finagle.Stack`s:
 *
 *  * `pathStack` is a per-[[com.twitter.finagle.buoyant.Dst.Path
 *    Dst.Path]] Stack segment.  When this Stack is applied, the
 *    [[com.twitter.finagle.buoyant.Dst.Path Dst.Path]] `Stack.Param` is
 *    configured.  This type of destination has a destination
 *    `com.twitter.finagle.Path`, a base `com.twitter.finagle.Dtab`,
 *    and a local (usually per-request) `Dtab`.
 *
 *  * `boundStack` is a per-`com.twitter.finagle.buoyant.Dst.Bound`
 *    Stack segment.  When this Stack is applied, the
 *    [[com.twitter.finagle.buoyant.Dst.Bound Dst.Bound]] Stack.Param
 *    is configured.  This type of destination has an
 *    `com.twitter.finagle.Addr`, a bound id, and a residual
 *    path.
 *
 *  * `clientStack` is a per-
 *    `com.twitter.finagle.BindingFactory.Dest` Stack segment.  When
 *    this Stack is applied, the `BindingFactory.Dest` `Stack.Param`
 *    is configured with a `com.twitter.finagle.Name.Bound` that does
 *    not have a residual `com.twitter.finagle.Path`.
 *
 * The stack is divided into these layers so that caching may be
 * applied bottom-up.  This enables a `clientStack` to be shared by
 * multiple `boundStack` and a `boundStack` to be shared by multiple
 * `pathStack`s.  It is the `StackRouter`'s job to connect these
 * `Stack` segments and to set the proper
 * `com.twitter.finagle.Stack.Param`s as described above.
 */
trait StackRouter[Req, Rsp] extends Router[Req, Rsp]
  with Stack.Parameterized[StackRouter[Req, Rsp]] {

  def pathStack: Stack[ServiceFactory[Req, Rsp]]
  def withPathStack(stack: Stack[ServiceFactory[Req, Rsp]]): StackRouter[Req, Rsp]

  /** Prepend `pathStack` with the given filter. */
  def pathFiltered(f: Filter[Req, Rsp, Req, Rsp]): StackRouter[Req, Rsp]

  def boundStack: Stack[ServiceFactory[Req, Rsp]]
  def withBoundStack(stack: Stack[ServiceFactory[Req, Rsp]]): StackRouter[Req, Rsp]

  /** Prepend `boundStack` with the given filter. */
  def boundFiltered(f: Filter[Req, Rsp, Req, Rsp]): StackRouter[Req, Rsp]

  def clientStack: Stack[ServiceFactory[Req, Rsp]]
  def withClientStack(stack: Stack[ServiceFactory[Req, Rsp]]): StackRouter[Req, Rsp]

  /** Prepend `clientStack` with the given filter. */
  def clientFiltered(f: Filter[Req, Rsp, Req, Rsp]): StackRouter[Req, Rsp]

  /** The current parameter map used in this StackRouter */
  def params: Stack.Params
  def withParams(ps: Stack.Params): StackRouter[Req, Rsp]

  override def configured[P: Stack.Param](p: P): StackRouter[Req, Rsp]
  override def configured[P](psp: (P, Stack.Param[P])): StackRouter[Req, Rsp]
}

/**
 * The standard template implementation of [[StackRouter]].
 *
 * Provides Stack caching so that e.g. multiple per-path stacks may
 * share common underlying `boundStack` and `clientStack` service
 * factories.
 *
 * Implementers must provide a `newIdentifier()` implementation that
 * l
 */
trait StdStackRouter[Req, Rsp, This <: StdStackRouter[Req, Rsp, This]]
  extends StackRouter[Req, Rsp] { self =>

  /**
   * The router uses a protocol-specific StackClient to build the
   * underlying client so that the Router doesn't need to replicate
   * Transporter/Dispatcher/endpointer logic.
   */
  protected def client: StackClient[Req, Rsp]
  def clientStack: Stack[ServiceFactory[Req, Rsp]] = client.stack

  /**
   * A copy constructor in lieu of defining StackRouter as a case
   * class.
   */
  protected def copy1(
    pathStack: Stack[ServiceFactory[Req, Rsp]] = this.pathStack,
    boundStack: Stack[ServiceFactory[Req, Rsp]] = this.boundStack,
    client: StackClient[Req, Rsp] = this.client,
    params: Stack.Params = this.params
  ): This

  def withPathStack(stack: Stack[ServiceFactory[Req, Rsp]]): This =
    copy1(pathStack = stack)

  def withBoundStack(stack: Stack[ServiceFactory[Req, Rsp]]): This =
    copy1(boundStack = stack)

  def withClientStack(stack: Stack[ServiceFactory[Req, Rsp]]): This =
    copy1(client = client.withStack(stack))

  def pathFiltered(f: Filter[Req, Rsp, Req, Rsp]): This =
    withPathStack(toStackable(f) +: pathStack)

  def boundFiltered(f: Filter[Req, Rsp, Req, Rsp]): This =
    withBoundStack(toStackable(f) +: boundStack)

  def clientFiltered(f: Filter[Req, Rsp, Req, Rsp]): This =
    withClientStack(toStackable(f) +: clientStack)

  protected def toStackable(f: Filter[Req, Rsp, Req, Rsp]): Stackable[ServiceFactory[Req, Rsp]] = {
    val role = Stack.Role(f.getClass.getSimpleName)
    Filter.canStackFromFac.toStackable(role, f)
  }

  /** Creates a new StackClient with parameter `p`. */
  override def configured[P: Stack.Param](p: P): This =
    withParams(params + p)

  /** Java compat */
  override def configured[P](psp: (P, Stack.Param[P])): This = {
    val (p, sp) = psp
    configured(p)(sp)
  }

  /**
   * Creates a new StackClient with `params` used to configure this
   * StackClient's `stack`.
   */
  def withParams(params: Stack.Params): This =
    copy1(params = params)

  /**
   * Builds an Identifier, a function that yields a Dst for each request.
   */
  protected def newIdentifier(): RoutingFactory.Identifier[Req]

  /**
   * A Stack module that is pushed to the top of the `pathStack`.  and
   * materializes `boundStack` and `clientStack` below, with caching.
   */
  protected def router: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module[ServiceFactory[Req, Rsp]] {
      val role = RoutingFactory.role
      val description = RoutingFactory.description
      val parameters = Seq(
        implicitly[Stack.Param[DstBindingFactory.IdleTtl]],
        implicitly[Stack.Param[DstBindingFactory.Capacity]],
        implicitly[Stack.Param[DstBindingFactory.Namer]],
        implicitly[Stack.Param[param.Stats]]
      )

      def make(
        params: Stack.Params,
        pathStack: Stack[ServiceFactory[Req, Rsp]]
      ): Stack[ServiceFactory[Req, Rsp]] = {
        val label = params[param.Label] match {
          case param.Label("") =>
            val param.ProtocolLibrary(label) = params[param.ProtocolLibrary]
            label
          case param.Label(label) => label
        }
        val param.Stats(stats) = params[param.Stats]
        val clientStats = param.Stats(stats.scope("client"))

        // if this router has been configured as an originator, add a
        // gauge to reflect that in the router's stats
        val Originator.Param(originator) = params[Originator.Param]
        if (originator) { stats.provideGauge("originator")(1f) }

        def pathMk(dst: Dst.Path, sf: ServiceFactory[Req, Rsp]) = {
          val sr = stats.scope("service", dst.path.show.stripPrefix("/"))
          val stk = pathStack ++ Stack.Leaf(Endpoint, sf)

          val pathParams = params[StackRouter.Client.PerPathParams].paramsFor(dst.path)
          stk.make(params ++ pathParams + dst + param.Stats(sr) + param.Label(dst.path.show) +
            RouterLabel.Param(label))
        }

        def boundMk(bound: Dst.Bound, sf: ServiceFactory[Req, Rsp]) = {
          val stk = (boundStack ++ Stack.Leaf(Endpoint, sf))
          stk.make(params + bound)
        }

        def mkClientLabel(bound: Name.Bound): String = bound.id match {
          case null => "null"
          case path: Path => path.show.stripPrefix("/")
          case id: String => id.stripPrefix("/")
          case _ => "unknown"
        }

        def clientMk(bound: Name.Bound) = {
          val name = bound.id match {
            case id: Path => id
            case _ => Path.empty
          }
          val clientParams = params[StackRouter.Client.PerClientParams].paramsFor(name)
          // client stats are scoped by label within .newClient
          client.withParams(params ++ clientParams + clientStats)
            .newClient(bound, mkClientLabel(bound))
        }

        val DstBindingFactory.Namer(namer) = params[DstBindingFactory.Namer]
        val cache = new DstBindingFactory.Cached[Req, Rsp](
          clientMk _,
          pathMk _,
          boundMk _,
          namer,
          stats.scope("bindcache"),
          params[DstBindingFactory.Capacity],
          params[DstBindingFactory.BindingTimeout],
          params[DstBindingFactory.IdleTtl]
        )(params[param.Timer].timer)

        Stack.Leaf(role, new RoutingFactory(newIdentifier(), cache, label))
      }
    }

  def factory(): ServiceFactory[Req, Rsp] =
    (router +: pathStack).make(params)
}

object StackRouter {

  object Server {
    def newStack[Req, Rsp]: Stack[ServiceFactory[Req, Rsp]] =
      StackServer.newStack[Req, Rsp]
  }

  object Client {

    case class ClientParams(prefix: PathMatcher, mk: Map[String, String] => Stack.Params)

    case class PerClientParams(params: Seq[ClientParams]) {
      def paramsFor(name: Path): Stack.Params = {
        params.foldLeft(Stack.Params.empty) {
          case (params, ClientParams(prefix, mk)) =>
            prefix.extract(name) match {
              case Some(vars) => params ++ mk(vars)
              case None => params
            }
        }
      }
    }
    implicit object PerClientParams extends Stack.Param[PerClientParams] {
      val default: PerClientParams = PerClientParams(Seq.empty)
    }

    case class PathParams(prefix: PathMatcher, mk: Map[String, String] => Stack.Params)

    case class PerPathParams(params: Seq[PathParams]) {
      def paramsFor(name: Path): Stack.Params = {
        params.foldLeft(Stack.Params.empty) {
          case (params, PathParams(prefix, mk)) =>
            prefix.extract(name) match {
              case Some(vars) => params ++ mk(vars)
              case None => params
            }
        }
      }
    }
    implicit object PerPathParams extends Stack.Param[PerPathParams] {
      val default: PerPathParams = PerPathParams(Seq.empty)
    }

    /**
     * Install the ClassifiedTracing filter immediately above any
     * protocol-specific annotating tracing filters, to provide response
     * classification annotations (success, failure, or retryable).
     *
     * Augment the default client StatsFilter with a
     * per-logical-destination stats filter.
     */
    def mkStack[Req, Rsp](orig: Stack[ServiceFactory[Req, Rsp]]): Stack[ServiceFactory[Req, Rsp]] =
      orig.insertBefore(StackClient.Role.protoTracing, ClassifiedTracing.module[Req, Rsp])
        .insertBefore(StatsFilter.role, PerDstPathStatsFilter.module[Req, Rsp])
        .replace(StatsFilter.role, LocalClassifierStatsFilter.module[Req, Rsp])
        .insertBefore(Retries.Role, RetryBudgetModule.module[Req, Rsp])
  }

  def newPathStack[Req, Rsp]: Stack[ServiceFactory[Req, Rsp]] = {
    /*
     * The ordering here is very important:
     *
     * - At the top of the path stack, we measure tracing and stats so
     *   that we have a logical view of the request.  Success rate may
     *   be computed from these stats to reflect the upstream client's
     *   view of this endpoint.
     *
     * - The total timeout module sets a timeout for the request including all
     *   retries and therefore must be above the retries module.
     *
     * - A non-shared retry budget is instantiated for this stack by the
     *   RetryBudgetModule.
     *
     * - Application-level retries are controlled by [[ClassifiedRetries]].
     *
     * - Then, factoryToService is used to manage properly manage
     *   sessions. We need to ensure that the underlying factory
     *   (provided by the lower stacks) is provisioned for each
     *   request to accomdate terminated requests (e.g. HTTP/1.0)
     *
     * - The failureRecording module records errors encountered when
     *   acquiring a service from the underlying service factory. This
     *   must be installed below factoryToService in order to catch
     *   errors from the lower stacks (notably NoBrokersAvailable,
     *   etc).
     */
    val stk = new StackBuilder[ServiceFactory[Req, Rsp]](stack.nilStack)
    stk.push(failureRecording)
    stk.push(StackClient.Role.prepFactory, identity[ServiceFactory[Req, Rsp]](_))
    stk.push(factoryToService)
    stk.push(ResponseClassifierCtx.Setter.module)
    stk.push(ClassifiedRetries.module)
    stk.push(RetryBudgetModule.module)
    stk.push(TotalTimeout.module)
    stk.push(StatsFilter.module)
    stk.push(DstTracing.Path.module)
    stk.push(DstPathCtx.Setter.module)
    stk.push(PathRegistry.module)
    stk.result
  }

  def newBoundStack[Req, Rsp]: Stack[ServiceFactory[Req, Rsp]] = {
    val stk = new StackBuilder[ServiceFactory[Req, Rsp]](stack.nilStack)
    stk.push(DstTracing.Bound.module)
    stk.push(EncodeResidual.role, identity[ServiceFactory[Req, Rsp]](_))
    stk.push(DstBoundCtx.Setter.module)
    stk.result
  }

  val defaultParams: Stack.Params =
    StackClient.defaultParams +
      FailFastFactory.FailFast(false) +
      param.Stats(DefaultStatsReceiver.scope("rt"))

  /**
   * Analagous to c.t.f.FactoryToService.module, but is applied
   * unconditionally.
   *
   * Finagle's FactoryToService is not directly used because we don't
   * want the conditional behavior and we don't want to enable other
   * factory to service modules in i.e. the client stack.
   *
   * We effectively treat the path stack as application-level and the
   * bound and client stacks as session-level.
   */
  private def factoryToService[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module0[ServiceFactory[Req, Rsp]] {
      val role = FactoryToService.role
      val description = "Ensures that underlying service factory is properly provisioned for each request"
      def make(next: ServiceFactory[Req, Rsp]) = {
        // To reiterate the comment in finagle's FactoryToService:
        // this is too complicated.
        val service = Future.value(new FactoryToService(next) {
          override def close(deadline: Time) = Future.Unit
        })
        new ServiceFactoryProxy(next) {
          override def apply(conn: ClientConnection) = service
        }
      }
    }

  private def failureRecording[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module0[ServiceFactory[Req, Rsp]] {
      import RoutingFactory.Annotations._

      val role = Stack.Role("AcquisitionFailure")
      val description = "Record failures encountered when issuing downstream requests"

      def make(next: ServiceFactory[Req, Rsp]) =
        new ServiceFactoryProxy(next) {
          override def apply(conn: ClientConnection) =
            self(conn).onFailure(Failure.ClientAcquisition.record).map(mkService)
        }

      val mkService: Service[Req, Rsp] => Service[Req, Rsp] =
        (service: Service[Req, Rsp]) =>
          new ServiceProxy(service) {
            override def apply(req: Req) =
              self(req).onFailure(Failure.Service.record)
          }
    }
}
