package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client._
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.service.FailFastFactory
import com.twitter.finagle.stack.Endpoint
import com.twitter.finagle.stats.DefaultStatsReceiver

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
        val param.Stats(stats0) = params[param.Stats]
        val stats = stats0.scope(label)

        val param.Label(routerLabel) = params[param.Label]
        def mkClientLabel(bound: Name.Bound): String = bound.id match {
          case null => "null"
          case path: Path => path.show.stripPrefix("/")
          case id: String => id.stripPrefix("/")
          case _ => "unknown"
        }

        def pathMk(dst: Dst.Path, sf: ServiceFactory[Req, Rsp]) = {
          val sr = stats.scope("dst", "path", dst.path.show.stripPrefix("/"))
          val stk = pathStack ++ Stack.Leaf(Endpoint, sf)
          stk.make(params + dst + param.Stats(sr))
        }

        def boundMk(bound: Dst.Bound, sf: ServiceFactory[Req, Rsp]) = {
          val stk = (boundStack ++ Stack.Leaf(Endpoint, sf))
          stk.make(params + bound)
        }

        def newClient(bound: Name.Bound) =
          client.withParams(params)
            .configured(param.Stats(stats.scope("dst", "id")))
            .newClient(bound, mkClientLabel(bound)) // stats will get scoped by label here

        val DstBindingFactory.Namer(namer) = params[DstBindingFactory.Namer]
        val cache = new DstBindingFactory.Cached[Req, Rsp](
          newClient _,
          pathMk _,
          boundMk _,
          namer,
          stats.scope("bindcache"),
          params[DstBindingFactory.Capacity]
        )

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

    /**
     * Install the TlsClientPrep module below the endpoint stack so that it
     * may avail itself of any and all params to set TLS params.
     */
    def mkStack[Req, Rsp](orig: Stack[ServiceFactory[Req, Rsp]]): Stack[ServiceFactory[Req, Rsp]] =
      orig ++ (TlsClientPrep.nop[Req, Rsp] +: stack.nilStack)
  }

  def newPathStack[Req, Rsp]: Stack[ServiceFactory[Req, Rsp]] = {
    val stk = new StackBuilder[ServiceFactory[Req, Rsp]](stack.nilStack)
    stk.push(DstTracing.Path.module)
    stk.result
  }

  def newBoundStack[Req, Rsp]: Stack[ServiceFactory[Req, Rsp]] = {
    val stk = new StackBuilder[ServiceFactory[Req, Rsp]](stack.nilStack)
    stk.push(DstTracing.Bound.module)
    stk.push(EncodeResidual.role, identity[ServiceFactory[Req, Rsp]](_))
    stk.result
  }

  val defaultParams: Stack.Params =
    StackClient.defaultParams +
      FailFastFactory.FailFast(false) +
      param.Stats(DefaultStatsReceiver.scope("rt"))
}
