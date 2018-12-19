package io.buoyant.k8s

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.{Buf, Writer}
import com.twitter.util._
import io.buoyant.namer.RichActivity
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import org.scalatest.exceptions.TestFailedException
import scala.util.control.NoStackTrace

class EndpointsNamerTest extends FunSuite with Awaits {
  val WatchPath = "/api/v1/watch/"
  val SessionsPath = "namespaces/srv/endpoints/sessions"
  val NonWatchPath = "/api/v1/"
  val ServicesPath = "namespaces/srv/services/"
  object Rsps {
    val InitResourceVersion = "4962526"

    val Init = Buf.Utf8(
      s"""
         |{
         |  "kind": "Endpoints",
         |  "apiVersion": "v1",
         |  "metadata": {
         |    "name": "sessions",
         |    "namespace": "srv",
         |    "selfLink": "$NonWatchPath$SessionsPath",
         |    "uid": "6a698096-525e-11e5-9859-42010af01815",
         |    "resourceVersion": "4962526",
         |    "creationTimestamp": "2015-09-03T17:08:37Z"
         |  },
         |  "subsets": [
         |    {
         |      "addresses": [
         |        {
         |          "ip": "10.248.4.9",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "sessions-293kc",
         |            "uid": "69f5a7d2-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962471"
         |          }
         |        },
         |        {
         |          "ip": "10.248.7.11",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "sessions-mr9gb",
         |            "uid": "69f5b78e-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962524"
         |          }
         |        },
         |        {
         |          "ip": "10.248.8.9",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "sessions-nicom",
         |            "uid": "69f5b623-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962517"
         |          }
         |        }
         |      ],
         |      "ports": [
         |        {
         |          "name": "http",
         |          "port": 8083,
         |          "protocol": "TCP"
         |        },
         |        {
         |          "name": "admin",
         |          "port": 9990,
         |          "protocol": "TCP"
         |        }
         |      ]
         |    }
         |  ]
         |}""".stripMargin
    )

    val ScaleUp = Buf.Utf8(
      s"""
         |{
         |  "type": "MODIFIED",
         |  "object": {
         |    "kind": "Endpoints",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "sessions",
         |      "namespace": "srv",
         |      "selfLink": "$NonWatchPath$SessionsPath",
         |      "uid": "6a698096-525e-11e5-9859-42010af01815",
         |      "resourceVersion": "5319582",
         |      "creationTimestamp": "2015-09-03T17:08:37Z"
         |    },
         |    "subsets": [
         |      {
         |        "addresses": [
         |          {
         |            "ip": "10.248.1.11",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-09ujq",
         |              "uid": "669b7a4f-55ef-11e5-a801-42010af08a01",
         |              "resourceVersion": "5319581"
         |            }
         |          },
         |          {
         |            "ip": "10.248.4.9",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-293kc",
         |              "uid": "69f5a7d2-525e-11e5-9859-42010af01815",
         |              "resourceVersion": "4962471"
         |            }
         |          },
         |          {
         |            "ip": "10.248.7.11",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-mr9gb",
         |              "uid": "69f5b78e-525e-11e5-9859-42010af01815",
         |              "resourceVersion": "4962524"
         |            }
         |          },
         |          {
         |            "ip": "10.248.8.9",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-nicom",
         |              "uid": "69f5b623-525e-11e5-9859-42010af01815",
         |              "resourceVersion": "4962517"
         |            }
         |          }
         |        ],
         |        "ports": [
         |          {
         |            "name": "http",
         |            "port": 8083,
         |            "protocol": "TCP"
         |          },
         |          {
         |            "name": "admin",
         |            "port": 9990,
         |            "protocol": "TCP"
         |          }
         |        ]
         |      }
         |    ]
         |  }
         |}""".stripMargin
    )

    val ScaleDown = Buf.Utf8(
      s"""
         |{
         |  "type": "MODIFIED",
         |  "object": {
         |    "kind": "Endpoints",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "sessions",
         |      "namespace": "srv",
         |      "selfLink": "$NonWatchPath$SessionsPath",
         |      "uid": "6a698096-525e-11e5-9859-42010af01815",
         |      "resourceVersion": "5319605",
         |      "creationTimestamp": "2015-09-03T17:08:37Z"
         |    },
         |    "subsets": [
         |      {
         |        "addresses": [
         |          {
         |            "ip": "10.248.4.9",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-293kc",
         |              "uid": "69f5a7d2-525e-11e5-9859-42010af01815",
         |              "resourceVersion": "4962471"
         |            }
         |          },
         |          {
         |            "ip": "10.248.7.11",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-mr9gb",
         |              "uid": "69f5b78e-525e-11e5-9859-42010af01815",
         |              "resourceVersion": "4962524"
         |            }
         |          },
         |          {
         |            "ip": "10.248.8.9",
         |            "targetRef": {
         |              "kind": "Pod",
         |              "namespace": "srv",
         |              "name": "sessions-nicom",
         |              "uid": "69f5b623-525e-11e5-9859-42010af01815",
         |              "resourceVersion": "4962517"
         |            }
         |          }
         |        ],
         |        "ports": [
         |          {
         |            "name": "http",
         |            "port": 8083,
         |            "protocol": "TCP"
         |          },
         |          {
         |            "name": "admin",
         |            "port": 9990,
         |            "protocol": "TCP"
         |          }
         |        ]
         |      }
         |    ]
         |  }
         |}""".stripMargin
    )

    val Auth = Buf.Utf8(
      s"""
         |{
         |  "kind": "Endpoints",
         |  "metadata": {
         |    "name": "auth",
         |    "namespace": "srv",
         |    "selfLink": "${NonWatchPath}namespaces/srv/endpoints/auth",
         |    "uid": "6982a2fc-525e-11e5-9859-42010af01815",
         |    "resourceVersion": "4969546",
         |    "creationTimestamp": "2015-09-03T17:08:35Z"
         |  },
         |  "subsets": [
         |    {
         |      "addresses": [
         |        {
         |          "ip": "10.248.0.10",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "auth-vmcia",
         |            "uid": "690ef84c-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962519"
         |          }
         |        },
         |        {
         |          "ip": "10.248.1.9",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "auth-d8qnl",
         |            "uid": "690f06c8-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962570"
         |          }
         |        },
         |        {
         |          "ip": "10.248.5.9",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "auth-m79ya",
         |            "uid": "690f05df-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962464"
         |          }
         |        }
         |      ],
         |      "ports": [
         |        {
         |          "name": "http",
         |          "port": 8082,
         |          "protocol": "TCP"
         |        },
         |        {
         |          "name": "admin",
         |          "port": 9990,
         |          "protocol": "TCP"
         |        }
         |      ]
         |    }
         |  ]
         |}
         |""".stripMargin
    )
    val Projects = Buf.Utf8(
      s"""
         |{
         |  "kind": "Endpoints",
         |  "metadata": {
         |    "name": "projects",
         |    "namespace": "srv",
         |    "selfLink": "${NonWatchPath}namespaces/srv/endpoints/projects",
         |    "uid": "6c39393c-525e-11e5-9859-42010af01815",
         |    "resourceVersion": "4962611",
         |    "creationTimestamp": "2015-09-03T17:08:40Z"
         |  },
         |  "subsets": [
         |    {
         |      "addresses": [
         |        {
         |          "ip": "10.248.0.11",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "projects-h2zbp",
         |            "uid": "6bc6a899-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962606"
         |          }
         |        },
         |        {
         |          "ip": "10.248.7.12",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "projects-fzfv2",
         |            "uid": "6bc6b7be-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962607"
         |          }
         |        },
         |        {
         |          "ip": "10.248.8.10",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "projects-0o69j",
         |            "uid": "6bc6c27c-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962610"
         |          }
         |        }
         |      ],
         |      "ports": [
         |        {
         |          "name": "http",
         |          "port": 8087,
         |          "protocol": "TCP"
         |        },
         |        {
         |          "name": "admin",
         |          "port": 9990,
         |          "protocol": "TCP"
         |        }
         |      ]
         |    }
         |  ]
         |}
      """.stripMargin
    )
    val Events = Buf.Utf8(
      s"""
         |{
         |  "kind": "Endpoints",
         |  "metadata": {
         |    "name": "events",
         |    "namespace": "srv",
         |    "selfLink": "${NonWatchPath}namespaces/srv/endpoints/events",
         |    "uid": "67abfc86-525e-11e5-9859-42010af01815",
         |    "resourceVersion": "4962380",
         |    "creationTimestamp": "2015-09-03T17:08:32Z"
         |  },
         |  "subsets": [
         |    {
         |      "addresses": [
         |        {
         |          "ip": "10.248.0.9",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "events-6g3in",
         |            "uid": "673a6ebf-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962378"
         |          }
         |        },
         |        {
         |          "ip": "10.248.5.8",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "events-l8xyq",
         |            "uid": "673a68fe-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962374"
         |          }
         |        },
         |        {
         |          "ip": "10.248.6.8",
         |          "targetRef": {
         |            "kind": "Pod",
         |            "namespace": "srv",
         |            "name": "events-4hkt8",
         |            "uid": "673a664a-525e-11e5-9859-42010af01815",
         |            "resourceVersion": "4962350"
         |          }
         |        }
         |      ],
         |      "ports": [
         |        {
         |          "name": "http",
         |          "port": 8085,
         |          "protocol": "TCP"
         |        },
         |        {
         |          "name": "admin",
         |          "port": 9990,
         |          "protocol": "TCP"
         |        }
         |      ]
         |    }
         |  ]
         |}
      """.stripMargin
    )
    val Empty = Buf.Utf8(
      """
        |{
        |  "kind": "Endpoints",
        |  "metadata": {
        |    "name": "empty-subset",
        |    "namespace": "srv",
        |    "selfLink": "/api/v1/namespaces/srv/endpoints/empty-subset",
        |    "uid": "7d5bd683-525e-11e5-9859-42010af01815",
        |    "resourceVersion": "5962619",
        |    "creationTimestamp": "2016-09-03T17:08:41Z"
        |  },
        |  "subsets": null
        |}
      """.stripMargin
    )

    object Services {
      val Auth = Buf.Utf8(
        """
          |{
          | "kind": "Service",
          | "apiVersion": "v1",
          | "metadata": {
          |   "creationTimestamp": "2017-03-24T03:32:27Z",
          |   "labels": {
          |     "name": "auth"
          |   },
          |   "name": "auth",
          |   "namespace": "srv",
          |   "resourceVersion": "33186981",
          |   "selfLink": "/api/v1/namespaces/srv/services/auth",
          |   "uid": "8122d7d0-1042-11e7-b340-42010af00007"
          | },
          | "spec": {
          |   "clusterIP": "10.199.240.10",
          |   "ports": [
          |     {
          |       "name": "http",
          |       "port": 80,
          |       "protocol": "TCP",
          |       "targetPort": "http"
          |     },
          |     {
          |       "name": "admin",
          |       "port": 9990,
          |       "protocol": "TCP"
          |     }
          |   ],
          |   "selector": {
          |     "name": "auth"
          |   },
          |   "sessionAffinity": "None",
          |   "type": "LoadBalancer"
          | },
          | "status": {
          |   "loadBalancer": {
          |     "ingress": [
          |       {
          |         "hostname": "linkerd.io"
          |       }
          |     ]
          |   }
          | }
          |}
        """.stripMargin
      )
      val Sessions = Buf.Utf8(
        """{
          |  "kind": "Service",
          |  "apiVersion": "v1",
          |  "metadata": {
          |    "creationTimestamp": "2017-03-24T03:32:27Z",
          |    "labels": {
          |      "name": "sessions"
          |    },
          |    "name": "sessions",
          |    "namespace": "srv",
          |    "resourceVersion": "33186979",
          |    "selfLink": "/api/v1/namespaces/srv/services/sessions",
          |    "uid": "8122d7d0-1042-11e7-b340-42010af00004"
          |  },
          |  "spec": {
          |    "clusterIP": "10.199.240.9",
          |    "ports": [
          |      {
          |        "name": "http",
          |        "port": 80,
          |        "protocol": "TCP",
          |        "targetPort": 54321
          |      },
          |      {
          |        "name": "admin",
          |        "port": 9990,
          |        "protocol": "TCP"
          |      }
          |    ],
          |    "selector": {
          |      "name": "sessions"
          |    },
          |    "sessionAffinity": "None",
          |    "type": "LoadBalancer"
          |  },
          |  "status": {
          |    "loadBalancer": {
          |      "ingress": [
          |        {
          |          "ip": "35.184.61.229"
          |        }
          |      ]
          |    }
          |  }
          |}""".stripMargin
      )
      val Events = Buf.Utf8(
        """
          |{
          |  "kind": "Service",
          |  "apiVersion": "v1",
          |  "metadata": {
          |    "creationTimestamp": "2017-03-24T03:32:27Z",
          |    "labels": {
          |      "name": "events"
          |    },
          |    "name": "events",
          |    "namespace": "srv",
          |    "resourceVersion": "33186981",
          |    "selfLink": "/api/v1/namespaces/srv/services/events",
          |    "uid": "8122d7d0-1042-11e7-b340-42010af00006"
          |  },
          |  "spec": {
          |    "clusterIP": "10.199.240.9",
          |    "ports": [
          |      {
          |        "name": "http",
          |        "port": 80,
          |        "protocol": "TCP",
          |        "targetPort": 54321
          |      },
          |      {
          |        "name": "admin",
          |        "port": 9990,
          |        "protocol": "TCP"
          |      }
          |    ],
          |    "selector": {
          |      "name": "events"
          |    },
          |    "sessionAffinity": "None",
          |    "type": "LoadBalancer"
          |  },
          |  "status": {
          |    "loadBalancer": {
          |      "ingress": [
          |        {
          |          "hostname": "linkerd.io"
          |        }
          |      ]
          |    }
          |  }
          |}
        """.stripMargin
      )
      val Projects = Buf.Utf8(
        """
          |{
          |    "kind": "Service",
          |    "apiVersion": "v1",
          |    "metadata": {
          |      "creationTimestamp": "2017-03-24T03:32:27Z",
          |      "labels": {
          |        "name": "projects"
          |      },
          |      "name": "projects",
          |      "namespace": "srv",
          |      "resourceVersion": "33186980",
          |      "selfLink": "/api/v1/namespaces/srv/services/projects",
          |      "uid": "8122d7d0-1042-11e7-b340-42010af00005"
          |    },
          |    "spec": {
          |      "clusterIP": "10.199.240.9",
          |      "ports": [
          |        {
          |          "name": "http",
          |          "port": 80,
          |          "protocol": "TCP",
          |          "targetPort": 54321
          |        },
          |        {
          |          "name": "admin",
          |          "port": 9990,
          |          "protocol": "TCP"
          |        }
          |      ],
          |      "selector": {
          |        "name": "projects"
          |      },
          |      "sessionAffinity": "None",
          |      "type": "LoadBalancer"
          |    },
          |    "status": {
          |      "loadBalancer": {}
          |    }
          |}
        """.stripMargin
      )
      val Responses = Map[String, Buf](
        s"${NonWatchPath}namespaces/srv/services/sessions" -> Sessions,
        s"${NonWatchPath}namespaces/srv/services/auth" -> Auth,
        s"${NonWatchPath}namespaces/srv/services/empty-subset" -> Empty,
        s"${NonWatchPath}namespaces/srv/services/projects" -> Projects,
        s"${NonWatchPath}namespaces/srv/services/events" -> Events
      )
      val All = Buf
        .Utf8(
          """{"apiVersion":"v1","items":[{"metadata":{"creationTimestamp":"2017-03-24T03:32:27Z","labels":{"name":"sessions"},"name":"sessions","namespace":"srv","resourceVersion":"33186979","selfLink":"/api/v1/namespaces/srv/services/sessions","uid":"8122d7d0-1042-11e7-b340-42010af00004"},"spec":{"clusterIP":"10.199.240.9","ports":[{"name":"http","port":80,"protocol":"TCP","targetPort":54321},{"name":"admin","port":9990,"protocol":"TCP"}],"selector":{"name":"sessions"},"sessionAffinity":"None","type":"LoadBalancer"},"status":{"loadBalancer":{"ingress":[{"ip":"35.184.61.229"}]}}},{"metadata":{"creationTimestamp":"2017-03-24T03:32:27Z","labels":{"name":"projects"},"name":"projects","namespace":"srv","resourceVersion":"33186980","selfLink":"/api/v1/namespaces/srv/services/projects","uid":"8122d7d0-1042-11e7-b340-42010af00005"},"spec":{"clusterIP":"10.199.240.9","ports":[{"name":"http","port":80,"protocol":"TCP","targetPort":54321},{"name":"admin","port":9990,"protocol":"TCP"}],"selector":{"name":"projects"},"sessionAffinity":"None","type":"LoadBalancer"},"status":{"loadBalancer":{}}},{"metadata":{"creationTimestamp":"2017-03-24T03:32:27Z","labels":{"name":"events"},"name":"events","namespace":"srv","resourceVersion":"33186981","selfLink":"/api/v1/namespaces/srv/services/events","uid":"8122d7d0-1042-11e7-b340-42010af00006"},"spec":{"clusterIP":"10.199.240.9","ports":[{"name":"http","port":80,"protocol":"TCP","targetPort":54321},{"name":"admin","port":9990,"protocol":"TCP"}],"selector":{"name":"events"},"sessionAffinity":"None","type":"LoadBalancer"},"status":{"loadBalancer":{"ingress":[{"hostname":"linkerd.io"}]}}},{"metadata":{"creationTimestamp":"2017-03-24T03:32:27Z","labels":{"name":"auth"},"name":"auth","namespace":"srv","resourceVersion":"33186981","selfLink":"/api/v1/namespaces/srv/services/auth","uid":"8122d7d0-1042-11e7-b340-42010af00007"},"spec":{"clusterIP":"10.199.240.10","ports":[{"name":"http","port":80,"protocol":"TCP","targetPort":"http"},{"name":"admin","port":9990,"protocol":"TCP"}],"selector":{"name":"auth"},"sessionAffinity":"None","type":"LoadBalancer"},"status":{"loadBalancer":{"ingress":[{"hostname":"linkerd.io"}]}}}],"kind":"ServiceList","metadata":{"resourceVersion":"33787896","selfLink":"/api/v1/namespaces/srv/services"}}"""
        )
    }

    val Inits = Map[String, Buf](
      s"$NonWatchPath$SessionsPath" -> Init,
      s"${NonWatchPath}namespaces/srv/endpoints/auth" -> Auth,
      s"${NonWatchPath}namespaces/srv/endpoints/empty-subset" -> Empty,
      s"${NonWatchPath}namespaces/srv/endpoints/projects" -> Projects,
      s"${NonWatchPath}namespaces/srv/endpoints/events" -> Events
    )
  }

  // reproduction for Linkerd issue #1626
  object Rsps1626 {
    val Init = Buf.Utf8(
      """{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61412986","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.84","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-1mkr7","uid":"0d893850-9806-11e7-9078-42010af00004","resourceVersion":"61412979"}},{"ip":"10.196.5.85","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-vd6dg","uid":"0d89460f-9806-11e7-9078-42010af00004","resourceVersion":"61412980"}},{"ip":"10.196.5.86","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-z86bg","uid":"0d894db8-9806-11e7-9078-42010af00004","resourceVersion":"61412983"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}"""
    )
    val InitWatch = Buf.Utf8(
      """{"type":"ADDED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61412986","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.84","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-1mkr7","uid":"0d893850-9806-11e7-9078-42010af00004","resourceVersion":"61412979"}},{"ip":"10.196.5.85","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-vd6dg","uid":"0d89460f-9806-11e7-9078-42010af00004","resourceVersion":"61412980"}},{"ip":"10.196.5.86","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-z86bg","uid":"0d894db8-9806-11e7-9078-42010af00004","resourceVersion":"61412983"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val BeforeScaleDown = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492813","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.84","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-1mkr7","uid":"0d893850-9806-11e7-9078-42010af00004","resourceVersion":"61412979"}},{"ip":"10.196.5.85","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-vd6dg","uid":"0d89460f-9806-11e7-9078-42010af00004","resourceVersion":"61412980"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val ScaleDown = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492816","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[]}}"""
    )
    val ScaleUp1 = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492931","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.87","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-6x155","uid":"ef248ef2-988f-11e7-9078-42010af00004","resourceVersion":"61492928"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val ScaleUp2 = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492933","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.87","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-6x155","uid":"ef248ef2-988f-11e7-9078-42010af00004","resourceVersion":"61492928"}},{"ip":"10.196.5.89","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-whz9k","uid":"ef248a9c-988f-11e7-9078-42010af00004","resourceVersion":"61492930"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val ScaleUp3 = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492936","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.87","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-6x155","uid":"ef248ef2-988f-11e7-9078-42010af00004","resourceVersion":"61492928"}},{"ip":"10.196.5.88","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-w8qsk","uid":"ef249841-988f-11e7-9078-42010af00004","resourceVersion":"61492934"}},{"ip":"10.196.5.89","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-whz9k","uid":"ef248a9c-988f-11e7-9078-42010af00004","resourceVersion":"61492930"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
  }

  trait Fixtures {
    @volatile var doInit, didInit, doScaleUp, doScaleDown, doFail, didServices = new Promise[Unit]
    @volatile var writer: Writer[Buf] = null

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/watch/namespaces/srv/endpoints/sessions?resourceVersion=5319582" =>
        val rsp = Response()

        rsp.setChunked(true)
        doScaleDown before rsp.writer.write(Rsps.ScaleDown)

        Future.value(rsp)
      case req if Rsps.Inits.contains(req.uri) =>
        val rsp = Response()
        rsp.content = Rsps.Inits(req.uri)
        doInit before Future.value(rsp)
      case req if Rsps.Services.Responses.contains(req.uri) =>
        val rsp = Response()
        rsp.content = Rsps.Services.Responses(req.uri)
        Future.value(rsp).onSuccess { _ => val _ = didServices.setDone() }
      case req if req.uri.startsWith(s"$WatchPath$ServicesPath") =>
        val rsp = Response()
        Future.value(rsp)
      case req if req.uri.startsWith(s"$WatchPath$SessionsPath") =>
        val rsp = Response()

        rsp.setChunked(true)

        doScaleUp before rsp.writer.write(Rsps.ScaleUp) before {
          doScaleDown before rsp.writer.write(Rsps.ScaleDown)
        }

        doFail onSuccess { _ =>
          rsp.writer.fail(new ChannelClosedException with NoStackTrace)
        } before {
          doScaleDown before rsp.writer.write(Rsps.ScaleDown)
        }

        Future.value(rsp)

      case req if req.uri.startsWith(WatchPath) && !req.uri.contains("sessions") =>
        val rsp = Response()
        Future.value(rsp)
      case req =>
        // As a workaround for an issue where some tests would enter an
        // infinite retry loop rather than failing, manually throw a
        // `TestFailedException` rather than calling `fail()`.
        //
        // `fail()` may provide slightly more useful information about
        // the failure location, but there was a concurrency issue where
        // the namer would keep retrying infinitely even after `fail()` was
        // called, causing SBT to hang. curiously, this issue doesn't seem
        // to apply when tests are run from IntelliJ?
        throw new TestFailedException(s"unexpected request: $req", 1)
    }
    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    def name = "/srv/http/sessions"

    @volatile var stateUpdates: Int = 0
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    val activity = namer.lookup(Path.read(name))
    val _ = activity.states.respond { s =>
      state = s
      stateUpdates += 1
    }

    def addrs = state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
        bound.addr.sample() match {
          case Addr.Bound(addrs, _) =>
            addrs
          case addr =>
            throw new TestFailedException(
              s"expected bound addr, got $addr (after $stateUpdates state updates)", 1
            )
        }
      case v =>
        throw new TestFailedException(s"unexpected state: $v (after $stateUpdates state updates)", 1)
    }

    def assertHas(n: Int) =
      assert(addrs.size == n, s" (after $stateUpdates state updates)")
    def assertUpdates(n: Int) = assert(stateUpdates == n)
  }

  test("single ns namer uses passed in namespace") {
    @volatile var request: Request = null
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val service = Service.mk[Request, Response] {
      case req if req.uri == s"$NonWatchPath$SessionsPath" =>
        request = req
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)
      case req if req.uri.startsWith(s"$WatchPath$SessionsPath") =>
        request = req
        val rsp = Response()
        Future.value(rsp)
      case req if Rsps.Services.Responses.contains(req.uri) =>
        request = req
        val rsp = Response()
        rsp.content = Rsps.Services.Responses(req.uri)
        Future.value(rsp)
      case req if req.uri.startsWith(s"$WatchPath$ServicesPath") =>
        val rsp = Response()
        Future.value(rsp)
      case req =>
        throw new TestFailedException(s"unexpected request: $req", 1)
    }

    val api = v1.Api(service)
    val namer = new SingleNsNamer(Path.read("/test"), None, "srv", api.withNamespace, Stream.continually(1.millis))
    namer.lookup(Path.read("/http/sessions/d3adb33f")).states.respond { s =>
      state = s
    }

    assert(
      request.uri.startsWith(s"$WatchPath$SessionsPath") ||
        request.uri.startsWith(s"$NonWatchPath${ServicesPath}sessions"),
      "Request was not sent to correct namespace"
    )
    state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
        assert(bound.id == Path.Utf8("test", "http", "sessions"))
      case v =>
        fail(s"unexpected state: $v")
    }
  }

  test("watches a namespace and receives updates") {
    val _ = new Fixtures {
      assert(state == Activity.Pending)
      doInit.setDone()
      assertHas(3)

      doScaleUp.setDone()
      assertHas(4)

      doScaleDown.setDone()
      assertHas(3)
    }
  }

  test("retries initial failures") {
    Time.withCurrentTimeFrozen { time =>
      val _ = new Fixtures {
        assert(state == Activity.Pending)
        doInit.setException(new Exception("should be retried") with NoStackTrace)

        time.advance(1.millis)
        timer.tick()
        assert(state == Activity.Pending)

        doInit = new Promise[Unit]
        doInit.setDone()
        time.advance(1.millis)
        timer.tick()

        await(activity.toFuture)
        assertHas(3)
      }
    }
  }

  test("missing port names are negative") {
    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/srv/endpoints/sessions" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)
      case req if req.uri == "/api/v1/watch/namespaces/srv/endpoints/sessions" =>
        Future.value(Response())
      case req if req.uri.startsWith(s"$NonWatchPath${ServicesPath}sessions") =>
        val rsp = Response()
        rsp.content = Rsps.Services.Sessions
        Future.value(rsp)
      case req if req.uri.startsWith(s"$WatchPath$ServicesPath") =>
        val rsp = Response()
        Future.value(rsp)
      case req =>
        throw new TestFailedException(s"unexpected request: $req", 1)
    }
    val api = v1.Api(service)
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    val _ = namer.lookup(Path.read("/srv/thrift/sessions")).states respond { s =>
      state = s
    }

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("reconnects on reader error") {
    val _ = new Fixtures {
      assert(state == Activity.Pending)
      doInit.setDone()
      assertHas(3)

      doScaleUp.setDone()
      assertHas(4)

      doInit = new Promise[Unit]
      doFail.setDone()

      await(activity.toFuture)
      doScaleDown.setDone()

      await(activity.toFuture)
      assertHas(3)
    }
  }

  test("sets labelSelector if not None ") {
    @volatile var req: Request = null

    val service = Service.mk[Request, Response] {
      case r if r.path.startsWith(s"$NonWatchPath$SessionsPath") =>
        req = r
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)
      case r if r.path.startsWith(s"$WatchPath$SessionsPath") =>
        req = r
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)
      case req if req.uri.startsWith(s"$NonWatchPath${ServicesPath}sessions") =>
        val rsp = Response()
        rsp.content = Rsps.Services.Sessions
        Future.value(rsp)
      case r if r.path.startsWith(s"$WatchPath$ServicesPath") =>
        req = r

        val rsp = Response()
        Future.value(rsp)
      case r =>
        throw new TestFailedException(s"unexpected request: $r", 1)
    }

    val api = v1.Api(service)
    val namer = new MultiNsNamer(Path.read("/test"), Some("versionLabel"), api.withNamespace)
    await(namer.lookup(Path.read("/srv/thrift/sessions/d3adb33f")).toFuture)

    assert(req.uri.contains("labelSelector=versionLabel%3Dd3adb33f"))
  }

  test("NameTree doesn't update on endpoint change") {
    val _ = new Fixtures {
      assert(state == Activity.Pending)
      doInit.setDone()

      await(activity.toFuture)
      assertHas(3)
      assert(stateUpdates == 2)

      doScaleUp.setDone()

      await(activity.toFuture)
      assertHas(4)
      assert(stateUpdates == 2)

      doScaleDown.setDone()

      await(activity.toFuture)
      assertHas(3)
      assert(stateUpdates == 2)
    }
  }

  test("namer accepts port numbers") {
    val _ = new Fixtures {

      override def name = "/srv/80/sessions"

      assert(state == Activity.Pending)
      doInit.setDone()
      await(didServices)

      assert(addrs == Set(Address("10.248.4.9", 54321), Address("10.248.8.9", 54321), Address("10.248.7.11", 54321)))
    }
  }

  test("namer accepts port numbers without target port defined") {
    val _ = new Fixtures {

      override def name = "/srv/9990/sessions"

      assert(state == Activity.Pending)
      doInit.setDone()
      await(didServices)
      assert(addrs == Set(Address("10.248.4.9", 9990), Address("10.248.8.9", 9990), Address("10.248.7.11", 9990)))
    }
  }

  test("namer accepts port numbers when ingress is not defined") {
    val _ = new Fixtures {

      override def name = "/srv/80/projects"

      assert(state == Activity.Pending)
      doInit.setDone()

      assert(addrs == Set(Address("10.248.0.11", 54321), Address("10.248.7.12", 54321), Address("10.248.8.10", 54321)))
    }
  }

  test("namer accepts port numbers when ingress contains hostname") {
    val _ = new Fixtures {

      override def name = "/srv/80/events"

      assert(state == Activity.Pending)
      doInit.setDone()

      assert(addrs == Set(Address("10.248.0.9", 54321), Address("10.248.5.8", 54321), Address("10.248.6.8", 54321)))
    }
  }

  test("port numbers not defined in service resolve to neg") {
    val _ = new Fixtures {

      override def name = "/srv/555/sessions"

      assert(state == Activity.Pending)
      doInit.setDone()

      assert(state == Activity.Ok(NameTree.Neg))
    }
  }

  test("port numbers can map to named target port") {
    val _ = new Fixtures {

      override def name = "/srv/80/auth"

      assert(state == Activity.Pending)
      doInit.setDone()

      assert(addrs == Set(Address("10.248.0.10", 8082), Address("10.248.1.9", 8082), Address("10.248.5.9", 8082)))
    }
  }

  test("namer is case insensitive") {
    val _ = new Fixtures {
      override def name = "/sRv/HtTp/SeSsIoNs"
      assert(state == Activity.Pending)
      doInit.setDone()

      assertHas(3)

      doScaleUp.setDone()
      assertHas(4)

      doScaleDown.setDone()
      assertHas(3)
    }
  }

  test("namer handles endpoints will null subsets") {
    val _ = new Fixtures {
      override def name = "/srv/http/empty-subset"

      assert(state == Activity.Pending)
      doInit.setDone()

      await(activity.toFuture)
      assert(state == Activity.Ok(NameTree.Neg))
    }
  }

  test("routes to new endpoints after scale down followed by scale up") {
    // linkerd #1626 reproduction
    @volatile var writer: Writer[Buf] = null

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/liza1626/endpoints/world-v1" =>
        val rsp = Response()
        rsp.content = Rsps1626.Init
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/liza1626/endpoints/world-v1") =>
        val rsp = Response()
        rsp.setChunked(true)

        writer = rsp.writer

        Future.value(rsp)
    }
    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val activity = namer.lookup(Path.read("/liza1626/http/world-v1"))

    val _ = activity.states respond { s =>
      state = s
    }

    def addrs = state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
        bound.addr.sample() match {
          case Addr.Bound(addrs, _) =>
            addrs.map {
              // discard metadata
              case Address.Inet(inet, _) => Address(inet)
              case a => a
            }
          case addr =>
            throw new TestFailedException(
              s"expected bound addr, got $addr", 1
            )
        }
      case v =>
        throw new TestFailedException(s"unexpected state: $v", 1)
    }
    await(writer.write(Rsps1626.InitWatch))
    assert(addrs == Set(Address("10.196.5.84", 7778), Address("10.196.5.85", 7778), Address("10.196.5.86", 7778)))

    await(writer.write(Rsps1626.BeforeScaleDown))
    await(activity.toFuture)
    await(writer.write(Rsps1626.ScaleDown))
    withClue("after scale down") { assert(state == Activity.Ok(NameTree.Neg)) }

    await(writer.write(Rsps1626.ScaleUp1))
    await(writer.write(Rsps1626.ScaleUp2))
    await(writer.write(Rsps1626.ScaleUp3))
    withClue("after scale up") {
      assert(addrs == Set(Address("10.196.5.87", 7778), Address("10.196.5.88", 7778), Address("10.196.5.89", 7778)))
    }
  }

  test("initial get returns unexpected status") {
    @volatile var i = 0
    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/srv/endpoints/sessions" && i == 0 =>
        val rsp = Response()
        rsp.status = http.Status.NotAcceptable
        i += 1
        Future.value(rsp)
      case req if req.uri == "/api/v1/namespaces/srv/endpoints/sessions" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/srv/endpoints/sessions") =>
        val rsp = Response()
        rsp.setChunked(true)
        Future.value(rsp)
    }

    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val activity = namer.lookup(Path.read("/srv/http/sessions"))

    val addrs = await(activity.toFuture.map {
      case NameTree.Leaf(Name.Bound(vaddr)) => vaddr.sample() match {
        case Addr.Bound(addrs, _) => addrs
        case a => fail("unexpected Addr type: " + a)
      }
    })

    assert(addrs == Set(Address("10.248.4.9", 8083), Address("10.248.7.11", 8083), Address("10.248.8.9", 8083)))
  }

  test("watch returns unexpected status") {
    @volatile var i = 0
    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/srv/endpoints/sessions" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/srv/endpoints/sessions") && i == 0 =>
        val rsp = Response()
        rsp.status = http.Status.NotAcceptable
        i += 1
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/srv/endpoints/sessions") =>
        val rsp = Response()
        rsp.setChunked(true)
        rsp.writer.write(Rsps.ScaleUp)
        Future.value(rsp)
    }

    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    @volatile var state: Set[Address] = Set.empty

    val activity = namer.lookup(Path.read("/srv/http/sessions"))

    val closable = activity.values.collect {
      case Return(NameTree.Leaf(Name.Bound(vaddr))) => vaddr
    }.mergeMap { vaddr =>
      vaddr.changes
    }.respond { addr =>
      val Addr.Bound(addrs, _) = addr
      state = addrs
    }

    eventually {
      assert(
        state == Set(
          Address("10.248.4.9", 8083),
          Address("10.248.7.11", 8083),
          Address("10.248.8.9", 8083),
          Address("10.248.1.11", 8083)
        )
      )
    }

    closable.close()
  }

  test("existing Vars are updated when a service goes away and comes back") {
    // linkerd #1626 reproduction
    @volatile var writer: Writer[Buf] = null

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/liza1626/endpoints/world-v1" =>
        val rsp = Response()
        rsp.content = Rsps1626.Init
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/liza1626/endpoints/world-v1") =>
        val rsp = Response()
        rsp.setChunked(true)

        writer = rsp.writer

        Future.value(rsp)
    }
    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val activity = namer.lookup(Path.read("/liza1626/http/world-v1"))

    val _ = activity.states respond { s =>
      state = s
    }

    def addrs(s: Activity.State[NameTree[Name]]) = s match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
        bound.addr.sample() match {
          case Addr.Bound(addrs, _) =>
            addrs.map {
              // discard metadata
              case Address.Inet(inet, _) => Address(inet)
              case a => a
            }
          case addr =>
            throw new TestFailedException(
              s"expected bound addr, got $addr", 1
            )
        }
      case v =>
        throw new TestFailedException(s"unexpected state: $v", 1)
    }
    await(writer.write(Rsps1626.InitWatch))
    assert(addrs(state) == Set(Address("10.196.5.84", 7778), Address("10.196.5.85", 7778), Address("10.196.5.86", 7778)))
    val theVar = state
    await(writer.write(Rsps1626.BeforeScaleDown))
    await(activity.toFuture)
    await(writer.write(Rsps1626.ScaleDown))
    withClue("after scale down") { assert(state == Activity.Ok(NameTree.Neg)) }

    await(writer.write(Rsps1626.ScaleUp1))
    await(writer.write(Rsps1626.ScaleUp2))
    await(writer.write(Rsps1626.ScaleUp3))
    withClue("after scale up") {
      assert(addrs(theVar) == Set(Address("10.196.5.87", 7778), Address("10.196.5.88", 7778), Address("10.196.5.89", 7778)))
    }
  }

  test("out of order resource versions ignored") {
    @volatile var writer: Writer[Buf] = null

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/liza1626/endpoints/world-v1" =>
        val rsp = Response()
        rsp.content = Rsps1626.Init
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/liza1626/endpoints/world-v1") =>
        val rsp = Response()
        rsp.setChunked(true)

        writer = rsp.writer

        Future.value(rsp)
    }
    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new MultiNsNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val activity = namer.lookup(Path.read("/liza1626/http/world-v1"))

    val _ = activity.states respond { s =>
      state = s
    }

    def addrs = state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
        bound.addr.sample() match {
          case Addr.Bound(addrs, _) =>
            addrs.map {
              // discard metadata
              case Address.Inet(inet, _) => Address(inet)
              case a => a
            }
          case addr =>
            throw new TestFailedException(
              s"expected bound addr, got $addr", 1
            )
        }
      case v =>
        throw new TestFailedException(s"unexpected state: $v", 1)
    }
    await(writer.write(Rsps1626.ScaleUp1))
    assert(addrs.size == 1)
    await(writer.write(Rsps1626.ScaleUp2))
    assert(addrs.size == 2)

    await(writer.write(Rsps1626.ScaleUp3))
    withClue("after highest version number") {
      assert(addrs.size == 3)
    }
    await(writer.write(Rsps1626.ScaleUp2))
    withClue("after lower-numbered resource version") {
      assert(addrs.size == 3)
    }
    await(writer.write(Rsps1626.ScaleUp1))
    withClue("after lower-numbered resource version") {
      assert(addrs.size == 3)
    }
  }

}
