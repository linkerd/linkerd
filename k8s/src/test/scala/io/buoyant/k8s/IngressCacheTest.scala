package io.buoyant.k8s

import com.twitter.finagle.Service
import com.twitter.io.Buf
import com.twitter.util.Future
import com.twitter.util.Promise
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import org.scalatest.OptionValues
import com.twitter.finagle.http.{Request, Response}

class IngressCacheTest extends FunSuite with Awaits with OptionValues {
  val host = Some("myhost")
  val ns = Some("ns")

  val ingressResourceListWithManyAnnotatedIngresses =
    """
{
  "kind": "IngressList",
  "apiVersion": "extensions/v1beta1",
  "metadata": {
    "selfLink": "/apis/extensions/v1beta1/ingresses",
    "resourceVersion": "58101"
  },
  "items": [
    {
      "metadata": {
        "name": "istio-ingress",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/istio-ingress",
        "uid": "07900e6b-813d-11e7-b89b-080027a996b8",
        "resourceVersion": "58098",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:07:57Z",
        "annotations": {
          "kubernetes.io/ingress.class": "istio"
        }
      },
      "spec": {
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/istio-only",
                  "backend": {
                    "serviceName": "httpbin",
                    "servicePort": 6060
                  }
                },
                {
                  "path": "/shared",
                  "backend": {
                    "serviceName": "httpbin",
                    "servicePort": 6061
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    },
    {
      "metadata": {
        "name": "linkerd-ingress",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/linkerd-ingress",
        "uid": "07948e8e-813d-11e7-b89b-080027a996b8",
        "resourceVersion": "58099",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:07:57Z",
        "annotations": {
          "kubernetes.io/ingress.class": "linkerd"
        }
      },
      "spec": {
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/linkerd-only",
                  "backend": {
                    "serviceName": "echo",
                    "servicePort": 7070
                  }
                },
                {
                  "path": "/shared",
                  "backend": {
                    "serviceName": "echo",
                    "servicePort": 7071
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    },
    {
      "metadata": {
        "name": "nginx-ingress",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/nginx-ingress",
        "uid": "078c9288-813d-11e7-b89b-080027a996b8",
        "resourceVersion": "58096",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:07:57Z",
        "annotations": {
          "kubernetes.io/ingress.class": "nginx",
          "ingress.kubernetes.io/rewrite-target": "/"
        }
      },
      "spec": {
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/nginx-only",
                  "backend": {
                    "serviceName": "nginx",
                    "servicePort": 8080
                  }
                },
                {
                  "path": "/shared",
                  "backend": {
                    "serviceName": "nginx",
                    "servicePort": 8081
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    }
  ]
}"""

  val ingressResourceListWithMoreThanOneLinkerdIngresses =
    """
{
  "kind": "IngressList",
  "apiVersion": "extensions/v1beta1",
  "metadata": {
    "selfLink": "/apis/extensions/v1beta1/ingresses",
    "resourceVersion": "58101"
  },
  "items": [
    {
      "metadata": {
        "name": "linkerd-ingress",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/istio-ingress",
        "uid": "07900e6b-813d-11e7-b89b-080027a996b8",
        "resourceVersion": "58098",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:07:57Z",
        "annotations": {
          "kubernetes.io/ingress.class": "linkerd"
        }
      },
      "spec": {
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/linkerd-1",
                  "backend": {
                    "serviceName": "echo1",
                    "servicePort": 6060
                  }
                },
                {
                  "path": "/shared",
                  "backend": {
                    "serviceName": "shared1",
                    "servicePort": 6061
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    },
    {
      "metadata": {
        "name": "linkerd-ingress-2",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/linkerd-ingress",
        "uid": "07948e8e-813d-11e7-b89b-080027a996b8",
        "resourceVersion": "58099",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:07:57Z",
        "annotations": {
          "kubernetes.io/ingress.class": "linkerd"
        }
      },
      "spec": {
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/linkerd-2",
                  "backend": {
                    "serviceName": "echo2",
                    "servicePort": 7070
                  }
                },
                {
                  "path": "/shared",
                  "backend": {
                    "serviceName": "shared2",
                    "servicePort": 7071
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    }
  ]
}"""

  val ingressDeletedFromMoreThanOneLinkerdIngresses =
    """
{
  "type": "DELETED",
  "object": {
    "apiVersion": "extensions/v1beta1",
    "kind": "Ingress",
    "metadata": {
      "name": "linkerd-ingress",
      "namespace": "default",
      "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/istio-ingress",
      "uid": "07900e6b-813d-11e7-b89b-080027a996b8",
      "resourceVersion": "58098",
      "generation": 1,
      "creationTimestamp": "2017-08-14T22:07:57Z",
      "annotations": {
        "kubernetes.io/ingress.class": "linkerd"
      }
    },
    "spec": {
      "rules": [
        {
          "http": {
            "paths": [
              {
                "path": "/linkerd-1",
                "backend": {
                  "serviceName": "echo1",
                  "servicePort": 6060
                }
              },
              {
                "path": "/shared",
                "backend": {
                  "serviceName": "shared1",
                  "servicePort": 6061
                }
              }
            ]
          }
        }
      ]
    },
    "status": {
      "loadBalancer": {}
    }
  }
}"""

  val ingressResourceListWithOneIngress =
    """
{
  "kind": "IngressList",
  "apiVersion": "extensions/v1beta1",
  "metadata": {
    "selfLink": "/apis/extensions/v1beta1/ingresses",
    "resourceVersion": "58845"
  },
  "items": [
    {
      "metadata": {
        "name": "the-ingress",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/the-ingress",
        "uid": "6c1466d7-813e-11e7-b89b-080027a996b8",
        "resourceVersion": "58840",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:17:55Z"
      },
      "spec": {
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/some-path",
                  "backend": {
                    "serviceName": "echo",
                    "servicePort": 1010
                  }
                },
                {
                  "path": "/other-path",
                  "backend": {
                    "serviceName": "echo",
                    "servicePort": 2021
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    }
  ]
}"""

  val ingressResourceListWithOneIngressWithFallback =
    """
{
  "kind": "IngressList",
  "apiVersion": "extensions/v1beta1",
  "metadata": {
    "selfLink": "/apis/extensions/v1beta1/ingresses",
    "resourceVersion": "58845"
  },
  "items": [
    {
      "metadata": {
        "name": "the-ingress",
        "namespace": "default",
        "selfLink": "/apis/extensions/v1beta1/namespaces/default/ingresses/the-ingress",
        "uid": "6c1466d7-813e-11e7-b89b-080027a996b8",
        "resourceVersion": "58840",
        "generation": 1,
        "creationTimestamp": "2017-08-14T22:17:55Z"
      },
      "spec": {
        "backend": {
          "serviceName": "fallback",
          "servicePort": 2022
        },
        "rules": [
          {
            "http": {
              "paths": [
                {
                  "path": "/some-path",
                  "backend": {
                    "serviceName": "echo",
                    "servicePort": 1010
                  }
                },
                {
                  "path": "/other-path",
                  "backend": {
                    "serviceName": "echo",
                    "servicePort": 2021
                  }
                }
              ]
            }
          }
        ]
      },
      "status": {
        "loadBalancer": {}
      }
    }
  ]
}"""

  val annotationClass = "linkerd"

  def mkIngressApiServiceReturning(response: String) = Service.mk[Request, Response] {
    mkWatchedIngressApiServiceReturning(response, "")
  }

  def mkWatchedIngressApiServiceReturning(response: String, watchResponse: String) = Service.mk[Request, Response] {
    case req if req.uri.contains("/apis/extensions/v1beta1/ingresses") =>
      val rsp = Response()
      rsp.content = Buf.Utf8(response)
      Future.value(rsp)
    case req if req.uri.contains("/apis/extensions/v1beta1/watch/ingresses") =>
      val rsp = Response()
      rsp.setChunked(true)
      rsp.writer.write(Buf.Utf8(watchResponse))
      Future.value(rsp)
    case req =>
      fail(s"unexpected request for [${req.uri}]: $req")
  }

  test("when multiple ingress resources, only adds one with desired annotation class") {
    val service = mkIngressApiServiceReturning(ingressResourceListWithManyAnnotatedIngresses)
    val cache = new IngressCache(None, service, annotationClass)
    assert(await(cache.matchPath(host, "/istio-only")).isEmpty)
    assert(await(cache.matchPath(host, "/nginx-only")).isEmpty)
    assert(await(cache.matchPath(host, "/non-existing-path")).isEmpty)
    assert(await(cache.matchPath(host, "/linkerd-only")).value.svc == "echo")
  }

  test("when multiple ingress resources with desired annotation class, matches based on path of either") {
    val service = mkIngressApiServiceReturning(ingressResourceListWithMoreThanOneLinkerdIngresses)
    val cache = new IngressCache(None, service, annotationClass)
    assert(await(cache.matchPath(host, "/linkerd-1")).value.svc == "echo1")
    assert(await(cache.matchPath(host, "/linkerd-2")).value.svc == "echo2")
    assert(Set("shared1", "shared2").contains(await(cache.matchPath(host, "/shared")).value.svc))
  }

  test("when only one ingress configured, adds it irrespective of annotations") {
    val service = mkIngressApiServiceReturning(ingressResourceListWithOneIngress)
    val cache = new IngressCache(None, service, annotationClass)
    assert(await(cache.matchPath(host, "/some-path")).value.svc == "echo")
    assert(await(cache.matchPath(host, "/non-existing-path")).isEmpty)
  }

  test("ignores port in a request's hostname") {
    val service = mkIngressApiServiceReturning(ingressResourceListWithOneIngress)
    val cache = new IngressCache(None, service, annotationClass)
    assert(await(cache.matchPath(Some("myhost:80"), "/some-path")).value.svc == "echo")
  }

  test("ingress caches honor default backends") {
    val service = mkIngressApiServiceReturning(ingressResourceListWithOneIngressWithFallback)
    val cache = new IngressCache(None, service, annotationClass)
    assert(await(cache.matchPath(host, "/some-path")).value.svc == "echo")
    assert(await(cache.matchPath(host, "/unknown-path")).value.svc == "fallback")
  }

  test("ignoreDefaultBackends ingress caches ignore default backends") {
    val service = mkIngressApiServiceReturning(ingressResourceListWithOneIngressWithFallback)
    val cache = new IngressCache(None, service, annotationClass, true)
    assert(await(cache.matchPath(host, "/some-path")).value.svc == "echo")
    assert(await(cache.matchPath(host, "/unknown-path")) == None)
  }

  test("deleting an ingress does not reset the ingress cache") {
    val service = mkWatchedIngressApiServiceReturning(
      ingressResourceListWithMoreThanOneLinkerdIngresses,
      ingressDeletedFromMoreThanOneLinkerdIngresses
    )
    val cache = new IngressCache(None, service, annotationClass, true)
    // The ingress that was deleted is gone.
    assert(await(cache.matchPath(host, "/linkerd-1")) == None)

    // Untouched ingresses remain.
    assert(await(cache.matchPath(host, "/linkerd-2")).value.svc == "echo2")
  }

  test("on multiple path matches, return first match") {
    val paths = Seq(
      IngressPath(host, Some("/path"), ns.get, "primary-svc", "80"),
      IngressPath(host, Some("/path"), ns.get, "secondary-svc", "80")
    )
    val spec = IngressSpec(Some("my-ingress"), ns, None, paths)
    val matchingPath = IngressCache.getMatchingPath(host, "/path", Seq(spec))
    assert(matchingPath.value.svc == "primary-svc")
  }

  test("on multiple host matches, return first match") {
    val resource1 = IngressSpec(Some("polar-bear1"), ns, None, Seq(IngressPath(host, None, ns.get, "svc1", "80")))
    val resource2 = IngressSpec(Some("polar-bear2"), ns, None, Seq(IngressPath(host, None, ns.get, "svc2", "80")))
    val matchingPath = IngressCache.getMatchingPath(host, "/path", Seq(resource1, resource2))
    assert(matchingPath.value.svc == "svc1")
  }

  test("don't return default backend before checking all resources for matches") {
    val resource1 = IngressSpec(
      Some("polar-bear1"),
      ns,
      Some(IngressPath(None, None, ns.get, "fallback", "80")),
      Seq(IngressPath(Some("other host"), None, ns.get, "svc1", "80"))
    )
    val resource2 = IngressSpec(Some("polar-bear2"), ns, None, Seq(IngressPath(host, None, ns.get, "svc2", "80")))
    val matchingPath = IngressCache.getMatchingPath(host, "/path", Seq(resource1, resource2))
    assert(matchingPath.value.svc == "svc2")
  }

  test("on no host/path matches, return first default backend") {
    val resource1 = IngressSpec(Some("polar-bear1"), ns, None, Seq(IngressPath(host, None, ns.get, "svc1", "80")))
    val resource2 = IngressSpec(
      Some("polar-bear2"),
      ns,
      Some(IngressPath(None, None, ns.get, "fallback", "80")),
      Seq(IngressPath(host, None, ns.get, "svc2", "80"))
    )
    val matchingPath = IngressCache.getMatchingPath(Some("unknown host"), "/path", Seq(resource1, resource2))
    assert(matchingPath.value.svc == "fallback")
  }

  test("no matches") {
    val resource1 = IngressSpec(Some("polar-bear1"), ns, None, Seq(IngressPath(host, None, ns.get, "svc1", "80")))
    val resource2 = IngressSpec(Some("polar-bear2"), ns, None, Seq(IngressPath(host, None, ns.get, "svc2", "80")))
    val matchingPath = IngressCache.getMatchingPath(Some("unknown host"), "/path", Seq(resource1, resource2))
    assert(matchingPath == None)
  }

  test("match on path regex") {
    val path = IngressPath(host, Some("/prefix/.*"), ns.get, "svc1", "80")
    assert(path.matches(host, "/prefix/and-other-stuff"))
  }

  test("match / with reqs that have empty paths only") {
    val path = IngressPath(host, Some("/"), ns.get, "svc1", "80")
    assert(path.matches(host, "/"))
    assert(!path.matches(host, "/foo"))
  }

  test("match empty string with all reqs") {
    val path = IngressPath(host, Some(""), ns.get, "svc1", "80")
    assert(path.matches(host, "/"))
    assert(path.matches(host, "/foo"))
  }

  test("match omitted path with all reqs") {
    val path = IngressPath(host, None, ns.get, "svc1", "80")
    assert(path.matches(host, "/"))
    assert(path.matches(host, "/foo"))
  }
}
