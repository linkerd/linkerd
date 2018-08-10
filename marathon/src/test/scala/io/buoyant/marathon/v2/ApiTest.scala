package io.buoyant.marathon.v2

import com.twitter.finagle.http.{Response, Request}
import com.twitter.finagle.{Address, Path, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.{Exceptions, Awaits}
import org.scalatest.FunSuite

class ApiTest extends FunSuite with Awaits with Exceptions {
  val appsBuf = Buf.Utf8("""
    {
      "apps": [
        {
          "id": "/foo",
          "cmd": null,
          "labels": { "LABEL_FOO": "BAR" }
        },
        {
          "id": "/bar",
          "cmd": "bar cmd"
        },
        {
          "id": "/baz",
          "cmd": null
        }
      ]
    }
  """)

  val appBuf = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "healthChecks": [
          {
            "gracePeriodSeconds": 30,
            "intervalSeconds": 5,
            "maxConsecutiveFailures": 10,
            "path": "/health",
            "portIndex": 0,
            "protocol": "MESOS_HTTP",
            "ipProtocol": "IPv4",
            "timeoutSeconds": 10,
            "delaySeconds": 15
          },
          {
            "gracePeriodSeconds": 15,
            "intervalSeconds": 10,
            "maxConsecutiveFailures": 10,
            "path": "/health2",
            "portIndex": 0,
            "protocol": "MESOS_HTTP",
            "ipProtocol": "IPv4",
            "timeoutSeconds": 15,
            "delaySeconds": 15
          }
        ],
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [7000, 7001, 7002],
            "healthCheckResults": [
              {
                "alive": true
              },
              {
                "alive": true
              }
            ],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [7003, 7004, 7005],
            "healthCheckResults": [
              {
                "alive": true
              },
              {
                "alive": false
              }
            ],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId3",
            "host": "9.10.11.12",
            "ports": [7006, 7007, 7008],
            "healthCheckResults": [
              {
                "alive": true
              },
              {
                "alive": true
              }
            ],
            "state": "TASK_KILLING"
          },
          {
            "id": "booksId4",
            "host": "13.14.15.16",
            "ports": [7009, 7010, 7011],
            "healthCheckResults": [],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId5",
            "host": "17.18.19.20",
            "ports": [7012, 7013, 7014],
            "healthCheckResults": [
              {
                "alive": true
              }
            ],
            "state": "TASK_RUNNING"
          }
        ]
      }
    }
  """)

  val ipAppBuf = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "healthChecks": [
          {
            "gracePeriodSeconds": 30,
            "intervalSeconds": 5,
            "maxConsecutiveFailures": 10,
            "path": "/health",
            "portIndex": 0,
            "protocol": "MESOS_HTTP",
            "ipProtocol": "IPv4",
            "timeoutSeconds": 10,
            "delaySeconds": 15
          },
          {
            "gracePeriodSeconds": 15,
            "intervalSeconds": 10,
            "maxConsecutiveFailures": 10,
            "path": "/health2",
            "portIndex": 0,
            "protocol": "MESOS_HTTP",
            "ipProtocol": "IPv4",
            "timeoutSeconds": 15,
            "delaySeconds": 15
          }
        ],
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.0",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [
              {
                "alive": true
              },
              {
                "alive": true
              }
            ],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.1",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [
              {
                "alive": true
              },
              {
                "alive": false
              }
            ],
            "state": "TASK_KILLING"
          },
          {
            "id": "booksId3",
            "host": "9.10.11.12",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.2",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [
              {
                "alive": true
              },
              {
                "alive": true
              }
            ],
            "state": "TASK_KILLING"
          },
          {
            "id": "booksId4",
            "host": "13.14.15.16",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.3",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId5",
            "host": "16.17.18.19",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.4",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [
              {
                "alive": true
              }
            ],
            "state": "TASK_RUNNING"
          }
        ],
        "ipAddress": {
          "discovery": {
            "ports": [
              {
                "labels": {},
                "name": "http",
                "number": 8080,
                "protocol": "tcp"
              }
            ]
          }
        }
      }
    }
  """)

  val appBufNoHealthCheck = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [7000, 7001, 7002],
            "healthCheckResults": [],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [7003, 7004, 7005],
            "healthCheckResults": [],
            "state": "TASK_KILLING"
          }
        ]
      }
    }
  """)

  val ipAppBufNoHealthCheck = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.0",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [7003, 7004, 7005],
            "healthCheckResults": [],
            "state": "TASK_KILLING"
          }
        ],
        "ipAddress": {
          "discovery": {
            "ports": [
              {
                "labels": {},
                "name": "http",
                "number": 8080,
                "protocol": "tcp"
              }
            ]
          }
        }
      }
    }
 """)

  val appBufEmptyHealthCheckList = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "healthChecks": [],
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [7000, 7001, 7002],
            "healthCheckResults": [],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [7003, 7004, 7005],
            "healthCheckResults": [],
            "state": "TASK_KILLING"
          }
        ]
      }
    }
 """)

  val ipAppBufEmptyHealthCheckList = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "healthChecks": [],
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [],
            "ipAddresses": [
              {
                "ipAddress": "250.1.62.0",
                "protocol": "IPv4"
              }
            ],
            "healthCheckResults": [],
            "state": "TASK_RUNNING"
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [7003, 7004, 7005],
            "healthCheckResults": [],
            "state": "TASK_KILLING"
          }
        ],
        "ipAddress": {
          "discovery": {
            "ports": [
              {
                "labels": {},
                "name": "http",
                "number": 8080,
                "protocol": "tcp"
              }
            ]
          }
        }
      }
    }
  """)

  val noApps = Buf.Utf8("""
    {"apps":[]}
  """)

  val appNotFoundBuf = Buf.Utf8("""{"message":"App '/foo' does not exist"}""")

  def stubService(buf: Buf) = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.content = buf
    Future.value(rsp)
  }

  test("getAppIds endpoint returns a seq of app names") {
    val service = stubService(appsBuf)

    val response = await(Api(service, "prefix", false).getAppIds())
    assert(response == Set(
      Path.read("/foo"),
      Path.read("/bar"),
      Path.read("/baz")
    ))
  }

  test("getAppIds endpoint returns an empty seq when there are no apps") {
    val service = stubService(noApps)

    val response = await(Api(service, "prefix", false).getAppIds())
    assert(response.size == 0)
  }

  test("getAddrs endpoint returns a seq of addresses") {
    val service = stubService(appBuf)

    val response = await(Api(service, "prefix", false).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("1.2.3.4", 7000),
      Address("5.6.7.8", 7003),
      Address("9.10.11.12", 7006),
      Address("13.14.15.16", 7009),
      Address("17.18.19.20", 7012)
    ))
  }

  test("getAddrs endpoint returns a seq of addresses for Ip-Per-Task") {
    val service = stubService(ipAppBuf)

    val response = await(Api(service, "prefix", false).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("250.1.62.0", 8080),
      Address("250.1.62.1", 8080),
      Address("250.1.62.2", 8080),
      Address("250.1.62.3", 8080),
      Address("250.1.62.4", 8080)
    ))
  }

  test("getAddrs endpoint returns an empty set of addresses if app not found") {
    val service = stubService(appNotFoundBuf)

    val response = await(Api(service, "prefix", false).getAddrs(Path.Utf8("foo")))
    assert(response.size == 0)
  }

  test("getAddrs endpoint returns a seq of healthy addresses when useHealthCheck is enabled") {
    val service = stubService(appBuf)

    val response = await(Api(service, "prefix", true).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("1.2.3.4", 7000)
    ))
  }

  test("getAddrs endpoint returns a seq of healthy addresses when useHealthCheck is enabled for Ip-Per-Task") {
    val service = stubService(ipAppBuf)

    val response = await(Api(service, "prefix", true).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("250.1.62.0", 8080)
    ))
  }

  test("getAddrs endpoint returns a seq of healthy addresses when useHealthCheck is enabled and no health checks are defined") {
    val service = stubService(appBufNoHealthCheck)

    val response = await(Api(service, "prefix", true).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("1.2.3.4", 7000)
    ))
  }

  test("getAddrs endpoint returns a seq of healthy addresses when useHealthCheck is enabled and health check list is empty") {
    val service = stubService(appBufEmptyHealthCheckList)

    val response = await(Api(service, "prefix", true).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("1.2.3.4", 7000)
    ))
  }

  test("getAddrs endpoint returns a seq of healthy addresses when useHealthCheck is enabled for Ip-Per-Task and no health checks are defined") {
    val service = stubService(ipAppBufNoHealthCheck)

    val response = await(Api(service, "prefix", true).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("250.1.62.0", 8080)
    ))
  }

  test("getAddrs endpoint returns a seq of healthy addresses when useHealthCheck is enabled for Ip-Per-Task and health check list is empty") {
    val service = stubService(ipAppBufEmptyHealthCheckList)

    val response = await(Api(service, "prefix", true).getAddrs(Path.Utf8("foo")))
    assert(response == Set(
      Address("250.1.62.0", 8080)
    ))
  }

  class ClientFailure extends Exception("I have no idea who to talk to")

  test("propagates client failures") {
    val failureService = Service.mk[Request, Response] { req =>
      Future.exception(new ClientFailure)
    }
    assertThrows[ClientFailure] {
      await(Api(failureService, "prefix", false).getAppIds())
    }
  }
}
