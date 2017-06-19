define([], function() {
return {
  "jvm": {
    "start_time": {
      "gauge": 1.48832833E+12
    },
    "application_time_millis": {
      "gauge": 4008938.8
    },
    "classes": {
      "total_loaded": {
        "gauge": 16890.0
      },
      "current_loaded": {
        "gauge": 16816.0
      },
      "total_unloaded": {
        "gauge": 74.0
      }
    },
    "postGC": {
      "Par_Survivor_Space": {
        "max": {
          "gauge": 2621440.0
        },
        "used": {
          "gauge": 0.0
        }
      },
      "CMS_Old_Gen": {
        "max": {
          "gauge": 1.79856998E+9
        },
        "used": {
          "gauge": 4.3641552E+8
        }
      },
      "Par_Eden_Space": {
        "max": {
          "gauge": 343670784
        },
        "used": {
          "gauge": 0.0
        }
      },
      "used": {
        "gauge": 4.3641552E+8
      }
    },
    "nonheap": {
      "committed": {
        "gauge": 155082752
      },
      "max": {
        "gauge": -1.0
      },
      "used": {
        "gauge": 152487792
      }
    },
    "tenuring_threshold": {
      "gauge": 0.0
    },
    "thread": {
      "daemon_count": {
        "gauge": 22.0
      },
      "count": {
        "gauge": 25.0
      },
      "peak_count": {
        "gauge": 34.0
      }
    },
    "mem": {
      "postGC": {
        "Par_Survivor_Space": {
          "max": {
            "gauge": 2621440.0
          },
          "used": {
            "gauge": 0.0
          }
        },
        "CMS_Old_Gen": {
          "max": {
            "gauge": 1.79856998E+9
          },
          "used": {
            "gauge": 4.3641552E+8
          }
        },
        "Par_Eden_Space": {
          "max": {
            "gauge": 343670784
          },
          "used": {
            "gauge": 0.0
          }
        },
        "used": {
          "gauge": 4.3641552E+8
        }
      },
      "metaspace": {
        "max_capacity": {
          "gauge": 1.13455923E+9
        }
      },
      "buffer": {
        "direct": {
          "max": {
            "gauge": 533504.0
          },
          "count": {
            "gauge": 16.0
          },
          "used": {
            "gauge": 533504.0
          }
        },
        "mapped": {
          "max": {
            "gauge": 0.0
          },
          "count": {
            "gauge": 0.0
          },
          "used": {
            "gauge": 0.0
          }
        }
      },
      "allocations": {
        "eden": {
          "bytes": {
            "gauge": 1.87168571E+11
          }
        }
      },
      "current": {
        "used": {
          "gauge": 8.0418445E+8
        },
        "CMS_Old_Gen": {
          "max": {
            "gauge": 1.79856998E+9
          },
          "used": {
            "gauge": 4.4002624E+8
          }
        },
        "Metaspace": {
          "max": {
            "gauge": -1.0
          },
          "used": {
            "gauge": 93440288
          }
        },
        "Par_Eden_Space": {
          "max": {
            "gauge": 343670784
          },
          "used": {
            "gauge": 211670448
          }
        },
        "Par_Survivor_Space": {
          "max": {
            "gauge": 2621440.0
          },
          "used": {
            "gauge": 0.0
          }
        },
        "Compressed_Class_Space": {
          "max": {
            "gauge": 1.07374182E+9
          },
          "used": {
            "gauge": 1.69364E+7
          }
        },
        "Code_Cache": {
          "max": {
            "gauge": 134217728
          },
          "used": {
            "gauge": 42111104
          }
        }
      }
    },
    "num_cpus": {
      "gauge": 4.0
    },
    "gc": {
      "msec": {
        "gauge": 13796.0
      },
      "eden": {
        "pause_msec": {
          "stat.count": 6,
          "stat.max": 21,
          "stat.min": 14,
          "stat.p50": 17,
          "stat.p90": 20,
          "stat.p95": 21,
          "stat.p99": 21,
          "stat.p9990": 21,
          "stat.p9999": 21,
          "stat.sum": 105,
          "stat.avg": 17.5
        }
      },
      "ParNew": {
        "msec": {
          "gauge": 10954.0
        },
        "cycles": {
          "gauge": 572.0
        }
      },
      "ConcurrentMarkSweep": {
        "msec": {
          "gauge": 2842.0
        },
        "cycles": {
          "gauge": 15.0
        }
      },
      "cycles": {
        "gauge": 587.0
      }
    },
    "fd_limit": {
      "gauge": 10240.0
    },
    "compilation": {
      "time_msec": {
        "gauge": 98650.0
      }
    },
    "uptime": {
      "gauge": 4029756.0
    },
    "safepoint": {
      "sync_time_millis": {
        "gauge": 618.0
      },
      "total_time_millis": {
        "gauge": 15179.0
      },
      "count": {
        "gauge": 4226.0
      }
    },
    "heap": {
      "committed": {
        "gauge": 1.13564467E+9
      },
      "max": {
        "gauge": 2.14486221E+9
      },
      "used": {
        "gauge": 6.516967E+8
      }
    },
    "fd_count": {
      "gauge": 473.0
    }
  },
  "zk2": {
    "inet": {
      "dns": {
        "queue_size": {
          "gauge": 0.0
        },
        "successes": {
          "counter": 0
        },
        "cache": {
          "evicts": {
            "gauge": 0.0
          },
          "size": {
            "gauge": 0.0
          },
          "hit_rate": {
            "gauge": 1.0
          }
        },
        "dns_lookups": {
          "counter": 0
        },
        "failures": {
          "counter": 0
        },
        "dns_lookup_failures": {
          "counter": 0
        },
        "lookup_ms": {
          "stat.count": 0
        }
      }
    },
    "observed_serversets": {
      "gauge": 0.0
    },
    "session_cache_size": {
      "gauge": 0.0
    }
  },
  "clnt": {
    "zipkin-tracer": {
      "connect_latency_ms": {
        "stat.count": 0
      },
      "failed_connect_latency_ms": {
        "stat.count": 1211,
        "stat.max": 5,
        "stat.min": 0,
        "stat.p50": 0,
        "stat.p90": 1,
        "stat.p95": 1,
        "stat.p99": 2,
        "stat.p9990": 4,
        "stat.p9999": 5,
        "stat.sum": 379,
        "stat.avg": 0.3129644921552436
      },
      "sent_bytes": {
        "counter": 0
      },
      "service_creation": {
        "failures": {
          "counter": 105764,
          "com.twitter.finagle.ChannelWriteException": {
            "counter": 105764,
            "java.net.ConnectException": {
              "counter": 105764
            }
          }
        }
      },
      "connection_received_bytes": {
        "stat.count": 1211,
        "stat.max": 0,
        "stat.min": 0,
        "stat.p50": 0,
        "stat.p90": 0,
        "stat.p95": 0,
        "stat.p99": 0,
        "stat.p9990": 0,
        "stat.p9999": 0,
        "stat.sum": 0,
        "stat.avg": 0.0
      },
      "connection_duration": {
        "stat.count": 1211,
        "stat.max": 70,
        "stat.min": 0,
        "stat.p50": 0,
        "stat.p90": 1,
        "stat.p95": 1,
        "stat.p99": 7,
        "stat.p9990": 70,
        "stat.p9999": 70,
        "stat.sum": 760,
        "stat.avg": 0.6275805119735756
      },
      "connects": {
        "counter": 105764
      },
      "pool_num_waited": {
        "counter": 0
      },
      "success": {
        "counter": 0
      },
      "request_latency_ms": {
        "stat.count": 0
      },
      "pool_waiters": {
        "gauge": 0.0
      },
      "received_bytes": {
        "counter": 0
      },
      "connection_sent_bytes": {
        "stat.count": 1211,
        "stat.max": 0,
        "stat.min": 0,
        "stat.p50": 0,
        "stat.p90": 0,
        "stat.p95": 0,
        "stat.p99": 0,
        "stat.p9990": 0,
        "stat.p9999": 0,
        "stat.sum": 0,
        "stat.avg": 0.0
      },
      "connection_requests": {
        "stat.count": 1211,
        "stat.max": 0,
        "stat.min": 0,
        "stat.p50": 0,
        "stat.p90": 0,
        "stat.p95": 0,
        "stat.p99": 0,
        "stat.p9990": 0,
        "stat.p9999": 0,
        "stat.sum": 0,
        "stat.avg": 0.0
      },
      "pool_num_too_many_waiters": {
        "counter": 0
      },
      "socket_unwritable_ms": {
        "counter": 0
      },
      "closes": {
        "counter": 211528
      },
      "pool_cached": {
        "gauge": 0.0
      },
      "pool_size": {
        "gauge": 0.0
      },
      "exn": {
        "java.net.ConnectException": {
          "counter": 105764
        }
      },
      "available": {
        "gauge": 1.0
      },
      "socket_writable_ms": {
        "counter": 0
      },
      "cancelled_connects": {
        "counter": 0
      },
      "codec_connection_preparation_latency_ms": {
        "stat.count": 1211,
        "stat.max": 5,
        "stat.min": 0,
        "stat.p50": 0,
        "stat.p90": 1,
        "stat.p95": 1,
        "stat.p99": 3,
        "stat.p9990": 5,
        "stat.p9999": 5,
        "stat.sum": 405,
        "stat.avg": 0.3344343517753922
      },
      "dtab": {
        "size": {
          "stat.count": 0
        }
      },
      "requests": {
        "counter": 0
      },
      "pending": {
        "gauge": 0.0
      },
      "connections": {
        "gauge": 0.0
      }
    }
  },
  "rt": {
    "subtractor": {
      "bindcache": {
        "path": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 7
          },
          "oneshots": {
            "counter": 0
          }
        },
        "bound": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        },
        "tree": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        },
        "client": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        }
      },
      "server": {
        "0.0.0.0/4115": {
          "success": {
            "counter": 0
          },
          "request_latency_ms": {
            "stat.count": 0
          },
          "transit_latency_ms": {
            "stat.count": 0
          },
          "request_payload_bytes": {
            "stat.count": 0
          },
          "response_payload_bytes": {
            "stat.count": 0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 0
          },
          "pending": {
            "gauge": 0.0
          },
          "handletime_us": {
            "stat.count": 0
          }
        }
      }
    },
    "divider": {
      "bindcache": {
        "path": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        },
        "bound": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        },
        "tree": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        },
        "client": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 0
          },
          "oneshots": {
            "counter": 0
          }
        }
      },
      "server": {
        "0.0.0.0/4117": {
          "success": {
            "counter": 0
          },
          "request_latency_ms": {
            "stat.count": 0
          },
          "transit_latency_ms": {
            "stat.count": 0
          },
          "request_payload_bytes": {
            "stat.count": 0
          },
          "response_payload_bytes": {
            "stat.count": 0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 0
          },
          "pending": {
            "gauge": 0.0
          },
          "handletime_us": {
            "stat.count": 0
          }
        }
      }
    },
    "adder": {
      "service": {
        "svc": {
          "success": {
            "counter": 315433
          },
          "request_latency_ms": {
            "stat.count": 3434,
            "stat.max": 316,
            "stat.min": 0,
            "stat.p50": 2,
            "stat.p90": 3,
            "stat.p95": 4,
            "stat.p99": 9,
            "stat.p9990": 55,
            "stat.p9999": 316,
            "stat.sum": 6081,
            "stat.avg": 1.7708211997670356
          },
          "retries": {
            "per_request": {
              "stat.count": 3434,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "total": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            }
          },
          "requests": {
            "counter": 315433
          },
          "pending": {
            "gauge": 0.0
          }
        },
        "client": {
          "$/inet/127.1/9091": {
            "connect_latency_ms": {
              "stat.count": 86,
              "stat.max": 6,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 2,
              "stat.p9990": 6,
              "stat.p9999": 6,
              "stat.sum": 8,
              "stat.avg": 0.09302325581395349
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 454350
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 159,
                "stat.max": 7,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 1,
                "stat.p99": 1,
                "stat.p9990": 7,
                "stat.p9999": 7,
                "stat.sum": 17,
                "stat.avg": 0.1069182389937107
              }
            },
            "connection_received_bytes": {
              "stat.count": 87,
              "stat.max": 230,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 69,
              "stat.p95": 92,
              "stat.p99": 161,
              "stat.p9990": 230,
              "stat.p9999": 230,
              "stat.sum": 3680,
              "stat.avg": 42.298850574712645
            },
            "connection_duration": {
              "stat.count": 87,
              "stat.max": 2268,
              "stat.min": 20,
              "stat.p50": 41,
              "stat.p90": 1379,
              "stat.p95": 1538,
              "stat.p99": 1877,
              "stat.p9990": 2268,
              "stat.p9999": 2268,
              "stat.sum": 27822,
              "stat.avg": 319.7931034482759
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7887
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 15145
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 159,
                  "stat.max": 4,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 1,
                  "stat.p99": 3,
                  "stat.p9990": 4,
                  "stat.p9999": 4,
                  "stat.sum": 23,
                  "stat.avg": 0.14465408805031446
                },
                "success": {
                  "counter": 15145
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 15145
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 159,
              "stat.max": 4,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 1,
              "stat.p99": 3,
              "stat.p9990": 4,
              "stat.p9999": 4,
              "stat.sum": 23,
              "stat.avg": 0.14465408805031446
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 159,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {},
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 348335
            },
            "connection_sent_bytes": {
              "stat.count": 87,
              "stat.max": 301,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 90,
              "stat.p95": 121,
              "stat.p99": 211,
              "stat.p9990": 301,
              "stat.p9999": 301,
              "stat.sum": 4800,
              "stat.avg": 55.172413793103445
            },
            "connection_requests": {
              "stat.count": 87,
              "stat.max": 10,
              "stat.min": 1,
              "stat.p50": 1,
              "stat.p90": 3,
              "stat.p95": 4,
              "stat.p99": 7,
              "stat.p9990": 10,
              "stat.p9999": 10,
              "stat.sum": 160,
              "stat.avg": 1.839080459770115
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7887
            },
            "pool_cached": {
              "gauge": 0.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 0.0
            },
            "request_payload_bytes": {
              "stat.count": 159,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 4770,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 159,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 3657,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 15145
            },
            "loadbalancer": {
              "size": {},
              "rebuilds": {
                "counter": 7912
              },
              "closed": {},
              "load": {},
              "meanweight": {},
              "adds": {
                "counter": 7887
              },
              "p2c": {
                "gauge": 22.0
              },
              "updates": {
                "counter": 7912
              },
              "available": {},
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {},
              "removes": {
                "counter": 7887
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 0.0
            }
          },
          "$/inet/127.1/9090": {
            "connect_latency_ms": {
              "stat.count": 92,
              "stat.max": 31,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 3,
              "stat.p9990": 31,
              "stat.p9999": 31,
              "stat.sum": 37,
              "stat.avg": 0.40217391304347827
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 458460
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 187,
                "stat.max": 32,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 1,
                "stat.p99": 2,
                "stat.p9990": 32,
                "stat.p9999": 32,
                "stat.sum": 46,
                "stat.avg": 0.24598930481283424
              }
            },
            "connection_received_bytes": {
              "stat.count": 91,
              "stat.max": 230,
              "stat.min": 23,
              "stat.p50": 46,
              "stat.p90": 92,
              "stat.p95": 115,
              "stat.p99": 207,
              "stat.p9990": 230,
              "stat.p9999": 230,
              "stat.sum": 4278,
              "stat.avg": 47.010989010989015
            },
            "connection_duration": {
              "stat.count": 91,
              "stat.max": 2290,
              "stat.min": 18,
              "stat.p50": 45,
              "stat.p90": 1508,
              "stat.p95": 1601,
              "stat.p99": 2094,
              "stat.p9990": 2290,
              "stat.p9999": 2290,
              "stat.sum": 31468,
              "stat.avg": 345.8021978021978
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7937
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 15282
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 187,
                  "stat.max": 55,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 0,
                  "stat.p99": 1,
                  "stat.p9990": 55,
                  "stat.p9999": 55,
                  "stat.sum": 59,
                  "stat.avg": 0.3155080213903743
                },
                "success": {
                  "counter": 15282
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 15282
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 187,
              "stat.max": 55,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 55,
              "stat.p9999": 55,
              "stat.sum": 59,
              "stat.avg": 0.3155080213903743
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 187,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {},
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 351486
            },
            "connection_sent_bytes": {
              "stat.count": 91,
              "stat.max": 301,
              "stat.min": 30,
              "stat.p50": 60,
              "stat.p90": 121,
              "stat.p95": 150,
              "stat.p99": 270,
              "stat.p9990": 301,
              "stat.p9999": 301,
              "stat.sum": 5580,
              "stat.avg": 61.31868131868132
            },
            "connection_requests": {
              "stat.count": 91,
              "stat.max": 10,
              "stat.min": 1,
              "stat.p50": 2,
              "stat.p90": 4,
              "stat.p95": 5,
              "stat.p99": 9,
              "stat.p9990": 10,
              "stat.p9999": 10,
              "stat.sum": 186,
              "stat.avg": 2.043956043956044
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7937
            },
            "pool_cached": {
              "gauge": 0.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 0.0
            },
            "request_payload_bytes": {
              "stat.count": 187,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 5610,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 187,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 4301,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 15282
            },
            "loadbalancer": {
              "size": {},
              "rebuilds": {
                "counter": 7960
              },
              "closed": {},
              "load": {},
              "meanweight": {},
              "adds": {
                "counter": 7937
              },
              "p2c": {
                "gauge": 27.0
              },
              "updates": {
                "counter": 7960
              },
              "available": {},
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {},
              "removes": {
                "counter": 7937
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 0.0
            }
          },
          "$/inet/127.1/9093": {
            "connect_latency_ms": {
              "stat.count": 82,
              "stat.max": 49,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 2,
              "stat.p9990": 49,
              "stat.p9999": 49,
              "stat.sum": 52,
              "stat.avg": 0.6341463414634146
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 445680
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 169,
                "stat.max": 49,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 1,
                "stat.p99": 3,
                "stat.p9990": 49,
                "stat.p9999": 49,
                "stat.sum": 66,
                "stat.avg": 0.3905325443786982
              }
            },
            "connection_received_bytes": {
              "stat.count": 82,
              "stat.max": 139,
              "stat.min": 23,
              "stat.p50": 46,
              "stat.p90": 92,
              "stat.p95": 115,
              "stat.p99": 139,
              "stat.p9990": 139,
              "stat.p9999": 139,
              "stat.sum": 3841,
              "stat.avg": 46.84146341463415
            },
            "connection_duration": {
              "stat.count": 82,
              "stat.max": 2158,
              "stat.min": 20,
              "stat.p50": 46,
              "stat.p90": 1421,
              "stat.p95": 1585,
              "stat.p99": 1877,
              "stat.p9990": 2158,
              "stat.p9999": 2158,
              "stat.sum": 23936,
              "stat.avg": 291.9024390243902
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7853
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 14856
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 169,
                  "stat.max": 1,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 0,
                  "stat.p99": 1,
                  "stat.p9990": 1,
                  "stat.p9999": 1,
                  "stat.sum": 6,
                  "stat.avg": 0.03550295857988166
                },
                "success": {
                  "counter": 14856
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 14856
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 169,
              "stat.max": 1,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 1,
              "stat.p9999": 1,
              "stat.sum": 6,
              "stat.avg": 0.03550295857988166
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 169,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {
                "gauge": 264.0
              },
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 341688
            },
            "connection_sent_bytes": {
              "stat.count": 82,
              "stat.max": 180,
              "stat.min": 30,
              "stat.p50": 60,
              "stat.p90": 121,
              "stat.p95": 150,
              "stat.p99": 180,
              "stat.p9990": 180,
              "stat.p9999": 180,
              "stat.sum": 5010,
              "stat.avg": 61.09756097560975
            },
            "connection_requests": {
              "stat.count": 82,
              "stat.max": 6,
              "stat.min": 1,
              "stat.p50": 2,
              "stat.p90": 4,
              "stat.p95": 5,
              "stat.p99": 6,
              "stat.p9990": 6,
              "stat.p9999": 6,
              "stat.sum": 167,
              "stat.avg": 2.0365853658536586
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7852
            },
            "pool_cached": {
              "gauge": 1.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 1.0
            },
            "request_payload_bytes": {
              "stat.count": 169,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 5070,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 169,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 3887,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 14856
            },
            "loadbalancer": {
              "size": {
                "gauge": 1.0
              },
              "rebuilds": {
                "counter": 7869
              },
              "closed": {
                "gauge": 0.0
              },
              "load": {
                "gauge": 0.0
              },
              "meanweight": {
                "gauge": 1.0
              },
              "adds": {
                "counter": 7853
              },
              "p2c": {
                "gauge": 26.0
              },
              "updates": {
                "counter": 7869
              },
              "available": {
                "gauge": 1.0
              },
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {
                "gauge": 0.0
              },
              "removes": {
                "counter": 7852
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 1.0
            }
          },
          "$/inet/127.1/9070": {
            "connect_latency_ms": {
              "stat.count": 85,
              "stat.max": 3,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 3,
              "stat.p9999": 3,
              "stat.sum": 4,
              "stat.avg": 0.047058823529411764
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 448860
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 183,
                "stat.max": 3,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 1,
                "stat.p9990": 3,
                "stat.p9999": 3,
                "stat.sum": 8,
                "stat.avg": 0.04371584699453552
              }
            },
            "connection_received_bytes": {
              "stat.count": 85,
              "stat.max": 161,
              "stat.min": 23,
              "stat.p50": 46,
              "stat.p90": 92,
              "stat.p95": 115,
              "stat.p99": 139,
              "stat.p9990": 161,
              "stat.p9999": 161,
              "stat.sum": 4209,
              "stat.avg": 49.51764705882353
            },
            "connection_duration": {
              "stat.count": 85,
              "stat.max": 2313,
              "stat.min": 17,
              "stat.p50": 49,
              "stat.p90": 1585,
              "stat.p95": 1859,
              "stat.p99": 2013,
              "stat.p9990": 2313,
              "stat.p9999": 2313,
              "stat.sum": 35877,
              "stat.avg": 422.08235294117645
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7832
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 14962
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 183,
                  "stat.max": 53,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 0,
                  "stat.p99": 1,
                  "stat.p9990": 53,
                  "stat.p9999": 53,
                  "stat.sum": 60,
                  "stat.avg": 0.32786885245901637
                },
                "success": {
                  "counter": 14962
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 14962
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 183,
              "stat.max": 53,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 53,
              "stat.p9999": 53,
              "stat.sum": 60,
              "stat.avg": 0.32786885245901637
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 183,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {
                "gauge": 264.0
              },
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 344126
            },
            "connection_sent_bytes": {
              "stat.count": 85,
              "stat.max": 211,
              "stat.min": 30,
              "stat.p50": 60,
              "stat.p90": 121,
              "stat.p95": 150,
              "stat.p99": 180,
              "stat.p9990": 211,
              "stat.p9999": 211,
              "stat.sum": 5490,
              "stat.avg": 64.58823529411765
            },
            "connection_requests": {
              "stat.count": 85,
              "stat.max": 7,
              "stat.min": 1,
              "stat.p50": 2,
              "stat.p90": 4,
              "stat.p95": 5,
              "stat.p99": 6,
              "stat.p9990": 7,
              "stat.p9999": 7,
              "stat.sum": 183,
              "stat.avg": 2.152941176470588
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7831
            },
            "pool_cached": {
              "gauge": 1.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 1.0
            },
            "request_payload_bytes": {
              "stat.count": 183,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 5490,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 183,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 4209,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 14962
            },
            "loadbalancer": {
              "size": {
                "gauge": 1.0
              },
              "rebuilds": {
                "counter": 7855
              },
              "closed": {
                "gauge": 0.0
              },
              "load": {
                "gauge": 0.0
              },
              "meanweight": {
                "gauge": 1.0
              },
              "adds": {
                "counter": 7832
              },
              "p2c": {
                "gauge": 25.0
              },
              "updates": {
                "counter": 7855
              },
              "available": {
                "gauge": 1.0
              },
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {
                "gauge": 0.0
              },
              "removes": {
                "counter": 7831
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 1.0
            }
          },
          "$/inet/127.1/9080": {
            "connect_latency_ms": {
              "stat.count": 94,
              "stat.max": 1,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 1,
              "stat.p9999": 1,
              "stat.sum": 3,
              "stat.avg": 0.031914893617021274
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 449130
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 179,
                "stat.max": 2,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 1,
                "stat.p9990": 2,
                "stat.p9999": 2,
                "stat.sum": 11,
                "stat.avg": 0.061452513966480445
              }
            },
            "connection_received_bytes": {
              "stat.count": 93,
              "stat.max": 161,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 92,
              "stat.p95": 92,
              "stat.p99": 139,
              "stat.p9990": 161,
              "stat.p9999": 161,
              "stat.sum": 4094,
              "stat.avg": 44.02150537634409
            },
            "connection_duration": {
              "stat.count": 93,
              "stat.max": 2290,
              "stat.min": 20,
              "stat.p50": 43,
              "stat.p90": 1379,
              "stat.p95": 1478,
              "stat.p99": 1859,
              "stat.p9990": 2290,
              "stat.p9999": 2290,
              "stat.sum": 28546,
              "stat.avg": 306.9462365591398
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7816
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 14971
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 179,
                  "stat.max": 301,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 0,
                  "stat.p99": 1,
                  "stat.p9990": 301,
                  "stat.p9999": 301,
                  "stat.sum": 309,
                  "stat.avg": 1.7262569832402235
                },
                "success": {
                  "counter": 14971
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 14971
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 179,
              "stat.max": 301,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 301,
              "stat.p9999": 301,
              "stat.sum": 308,
              "stat.avg": 1.7206703910614525
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 179,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {},
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 344333
            },
            "connection_sent_bytes": {
              "stat.count": 93,
              "stat.max": 211,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 121,
              "stat.p95": 121,
              "stat.p99": 180,
              "stat.p9990": 211,
              "stat.p9999": 211,
              "stat.sum": 5340,
              "stat.avg": 57.41935483870968
            },
            "connection_requests": {
              "stat.count": 93,
              "stat.max": 7,
              "stat.min": 1,
              "stat.p50": 1,
              "stat.p90": 4,
              "stat.p95": 4,
              "stat.p99": 6,
              "stat.p9990": 7,
              "stat.p9999": 7,
              "stat.sum": 178,
              "stat.avg": 1.913978494623656
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7816
            },
            "pool_cached": {
              "gauge": 0.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 0.0
            },
            "request_payload_bytes": {
              "stat.count": 179,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 5370,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 179,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 4117,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 14971
            },
            "loadbalancer": {
              "size": {},
              "rebuilds": {
                "counter": 7840
              },
              "closed": {},
              "load": {},
              "meanweight": {},
              "adds": {
                "counter": 7816
              },
              "p2c": {
                "gauge": 26.0
              },
              "updates": {
                "counter": 7840
              },
              "available": {},
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {},
              "removes": {
                "counter": 7816
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 0.0
            }
          },
          "$/inet/127.1/9085": {
            "connect_latency_ms": {
              "stat.count": 90,
              "stat.max": 46,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 1,
              "stat.p99": 1,
              "stat.p9990": 46,
              "stat.p9999": 46,
              "stat.sum": 50,
              "stat.avg": 0.5555555555555556
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 445650
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 155,
                "stat.max": 47,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 1,
                "stat.p9990": 47,
                "stat.p9999": 47,
                "stat.sum": 56,
                "stat.avg": 0.36129032258064514
              }
            },
            "connection_received_bytes": {
              "stat.count": 89,
              "stat.max": 161,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 69,
              "stat.p95": 92,
              "stat.p99": 139,
              "stat.p9990": 161,
              "stat.p9999": 161,
              "stat.sum": 3519,
              "stat.avg": 39.53932584269663
            },
            "connection_duration": {
              "stat.count": 89,
              "stat.max": 2313,
              "stat.min": 18,
              "stat.p50": 37,
              "stat.p90": 1352,
              "stat.p95": 1493,
              "stat.p99": 2013,
              "stat.p9990": 2313,
              "stat.p9999": 2313,
              "stat.sum": 20765,
              "stat.avg": 233.31460674157304
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7832
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 14855
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 155,
                  "stat.max": 1,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 0,
                  "stat.p99": 1,
                  "stat.p9990": 1,
                  "stat.p9999": 1,
                  "stat.sum": 4,
                  "stat.avg": 0.025806451612903226
                },
                "success": {
                  "counter": 14855
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 14855
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 155,
              "stat.max": 1,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 1,
              "stat.p9999": 1,
              "stat.sum": 4,
              "stat.avg": 0.025806451612903226
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 155,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {
                "gauge": 264.0
              },
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 341665
            },
            "connection_sent_bytes": {
              "stat.count": 89,
              "stat.max": 211,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 90,
              "stat.p95": 121,
              "stat.p99": 180,
              "stat.p9990": 211,
              "stat.p9999": 211,
              "stat.sum": 4590,
              "stat.avg": 51.57303370786517
            },
            "connection_requests": {
              "stat.count": 89,
              "stat.max": 7,
              "stat.min": 1,
              "stat.p50": 1,
              "stat.p90": 3,
              "stat.p95": 4,
              "stat.p99": 6,
              "stat.p9990": 7,
              "stat.p9999": 7,
              "stat.sum": 153,
              "stat.avg": 1.7191011235955056
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7831
            },
            "pool_cached": {
              "gauge": 1.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 1.0
            },
            "request_payload_bytes": {
              "stat.count": 155,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 4650,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 155,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 3565,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 14855
            },
            "loadbalancer": {
              "size": {
                "gauge": 1.0
              },
              "rebuilds": {
                "counter": 7848
              },
              "closed": {
                "gauge": 0.0
              },
              "load": {
                "gauge": 0.0
              },
              "meanweight": {
                "gauge": 1.0
              },
              "adds": {
                "counter": 7832
              },
              "p2c": {
                "gauge": 21.0
              },
              "updates": {
                "counter": 7848
              },
              "available": {
                "gauge": 1.0
              },
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {
                "gauge": 0.0
              },
              "removes": {
                "counter": 7831
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 1.0
            }
          },
          "$/inet/127.1/9089": {
            "connect_latency_ms": {
              "stat.count": 82,
              "stat.max": 48,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 1,
              "stat.p99": 1,
              "stat.p9990": 48,
              "stat.p9999": 48,
              "stat.sum": 52,
              "stat.avg": 0.6341463414634146
            },
            "failed_connect_latency_ms": {
              "stat.count": 0
            },
            "sent_bytes": {
              "counter": 447300
            },
            "service_creation": {
              "service_acquisition_latency_ms": {
                "stat.count": 176,
                "stat.max": 48,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 1,
                "stat.p99": 2,
                "stat.p9990": 48,
                "stat.p9999": 48,
                "stat.sum": 63,
                "stat.avg": 0.35795454545454547
              }
            },
            "connection_received_bytes": {
              "stat.count": 82,
              "stat.max": 185,
              "stat.min": 23,
              "stat.p50": 46,
              "stat.p90": 92,
              "stat.p95": 115,
              "stat.p99": 185,
              "stat.p9990": 185,
              "stat.p9999": 185,
              "stat.sum": 4048,
              "stat.avg": 49.36585365853659
            },
            "connection_duration": {
              "stat.count": 82,
              "stat.max": 2073,
              "stat.min": 20,
              "stat.p50": 46,
              "stat.p90": 1569,
              "stat.p95": 1633,
              "stat.p99": 1877,
              "stat.p9990": 2073,
              "stat.p9999": 2073,
              "stat.sum": 32237,
              "stat.avg": 393.1341463414634
            },
            "failure_accrual": {
              "removals": {
                "counter": 0
              },
              "probes": {
                "counter": 0
              },
              "removed_for_ms": {
                "counter": 0
              },
              "revivals": {
                "counter": 0
              }
            },
            "connects": {
              "counter": 7798
            },
            "pool_num_waited": {
              "counter": 0
            },
            "success": {
              "counter": 14910
            },
            "service": {
              "svc": {
                "request_latency_ms": {
                  "stat.count": 176,
                  "stat.max": 4,
                  "stat.min": 0,
                  "stat.p50": 0,
                  "stat.p90": 0,
                  "stat.p95": 0,
                  "stat.p99": 1,
                  "stat.p9990": 4,
                  "stat.p9999": 4,
                  "stat.sum": 11,
                  "stat.avg": 0.0625
                },
                "success": {
                  "counter": 14910
                },
                "pending": {
                  "gauge": 0.0
                },
                "requests": {
                  "counter": 14910
                }
              }
            },
            "request_latency_ms": {
              "stat.count": 176,
              "stat.max": 4,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 1,
              "stat.p9990": 4,
              "stat.p9999": 4,
              "stat.sum": 11,
              "stat.avg": 0.0625
            },
            "pool_waiters": {
              "gauge": 0.0
            },
            "retries": {
              "requeues_per_request": {
                "stat.count": 176,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "request_limit": {
                "counter": 0
              },
              "budget_exhausted": {
                "counter": 0
              },
              "cannot_retry": {
                "counter": 0
              },
              "not_open": {
                "counter": 0
              },
              "budget": {
                "gauge": 264.0
              },
              "requeues": {
                "counter": 0
              }
            },
            "received_bytes": {
              "counter": 342930
            },
            "connection_sent_bytes": {
              "stat.count": 82,
              "stat.max": 240,
              "stat.min": 30,
              "stat.p50": 60,
              "stat.p90": 121,
              "stat.p95": 150,
              "stat.p99": 240,
              "stat.p9990": 240,
              "stat.p9999": 240,
              "stat.sum": 5280,
              "stat.avg": 64.39024390243902
            },
            "connection_requests": {
              "stat.count": 82,
              "stat.max": 8,
              "stat.min": 1,
              "stat.p50": 2,
              "stat.p90": 4,
              "stat.p95": 5,
              "stat.p99": 8,
              "stat.p9990": 8,
              "stat.p9999": 8,
              "stat.sum": 176,
              "stat.avg": 2.1463414634146343
            },
            "pool_num_too_many_waiters": {
              "counter": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 7797
            },
            "pool_cached": {
              "gauge": 1.0
            },
            "pool_size": {
              "gauge": 0.0
            },
            "available": {
              "gauge": 1.0
            },
            "request_payload_bytes": {
              "stat.count": 176,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 5280,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "cancelled_connects": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 176,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 4048,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 14910
            },
            "loadbalancer": {
              "size": {
                "gauge": 1.0
              },
              "rebuilds": {
                "counter": 7809
              },
              "closed": {
                "gauge": 0.0
              },
              "load": {
                "gauge": 0.0
              },
              "meanweight": {
                "gauge": 1.0
              },
              "adds": {
                "counter": 7798
              },
              "p2c": {
                "gauge": 22.0
              },
              "updates": {
                "counter": 7809
              },
              "available": {
                "gauge": 1.0
              },
              "max_effort_exhausted": {
                "counter": 0
              },
              "busy": {
                "gauge": 0.0
              },
              "removes": {
                "counter": 7797
              }
            },
            "pending": {
              "gauge": 0.0
            },
            "dispatcher": {
              "serial": {
                "queue_size": {
                  "gauge": 0.0
                }
              }
            },
            "connections": {
              "gauge": 1.0
            }
          }
        }
      },
      "bindcache": {
        "path": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 1
          },
          "oneshots": {
            "counter": 0
          }
        },
        "bound": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 21
          },
          "oneshots": {
            "counter": 0
          }
        },
        "tree": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 1
          },
          "oneshots": {
            "counter": 0
          }
        },
        "client": {
          "evicts": {
            "counter": 165251
          },
          "misses": {
            "counter": 165261
          },
          "oneshots": {
            "counter": 0
          }
        },
        "server": {
          "0.0.0.0/4114": {
            "sent_bytes": {
              "counter": 7255005
            },
            "connection_received_bytes": {
              "stat.count": 34,
              "stat.max": 3026,
              "stat.min": 3026,
              "stat.p50": 3026,
              "stat.p90": 3026,
              "stat.p95": 3026,
              "stat.p99": 3026,
              "stat.p9990": 3026,
              "stat.p9999": 3026,
              "stat.sum": 103020,
              "stat.avg": 3030.0
            },
            "connection_duration": {
              "stat.count": 34,
              "stat.max": 701,
              "stat.min": 178,
              "stat.p50": 228,
              "stat.p90": 414,
              "stat.p95": 435,
              "stat.p99": 701,
              "stat.p9990": 701,
              "stat.p9999": 701,
              "stat.sum": 9579,
              "stat.avg": 281.7352941176471
            },
            "connects": {
              "counter": 3124
            },
            "success": {
              "counter": 315435
            },
            "request_latency_ms": {
              "stat.count": 3434,
              "stat.max": 316,
              "stat.min": 0,
              "stat.p50": 2,
              "stat.p90": 3,
              "stat.p95": 4,
              "stat.p99": 9,
              "stat.p9990": 55,
              "stat.p9999": 316,
              "stat.sum": 6237,
              "stat.avg": 1.8162492719860222
            },
            "received_bytes": {
              "counter": 9463080
            },
            "read_timeout": {
              "counter": 0
            },
            "write_timeout": {
              "counter": 0
            },
            "connection_sent_bytes": {
              "stat.count": 34,
              "stat.max": 2313,
              "stat.min": 2313,
              "stat.p50": 2313,
              "stat.p90": 2313,
              "stat.p95": 2313,
              "stat.p99": 2313,
              "stat.p9990": 2313,
              "stat.p9999": 2313,
              "stat.sum": 78982,
              "stat.avg": 2323.0
            },
            "connection_requests": {
              "stat.count": 34,
              "stat.max": 101,
              "stat.min": 101,
              "stat.p50": 101,
              "stat.p90": 101,
              "stat.p95": 101,
              "stat.p99": 101,
              "stat.p9990": 101,
              "stat.p9999": 101,
              "stat.sum": 3434,
              "stat.avg": 101.0
            },
            "transit_latency_ms": {
              "stat.count": 0
            },
            "socket_unwritable_ms": {
              "counter": 0
            },
            "closes": {
              "counter": 0
            },
            "request_payload_bytes": {
              "stat.count": 3434,
              "stat.max": 30,
              "stat.min": 30,
              "stat.p50": 30,
              "stat.p90": 30,
              "stat.p95": 30,
              "stat.p99": 30,
              "stat.p9990": 30,
              "stat.p9999": 30,
              "stat.sum": 103020,
              "stat.avg": 30.0
            },
            "socket_writable_ms": {
              "counter": 0
            },
            "response_payload_bytes": {
              "stat.count": 3434,
              "stat.max": 23,
              "stat.min": 23,
              "stat.p50": 23,
              "stat.p90": 23,
              "stat.p95": 23,
              "stat.p99": 23,
              "stat.p9990": 23,
              "stat.p9999": 23,
              "stat.sum": 78982,
              "stat.avg": 23.0
            },
            "dtab": {
              "size": {
                "stat.count": 0
              }
            },
            "requests": {
              "counter": 315435
            },
            "pending": {
              "gauge": 1.0
            },
            "handletime_us": {
              "stat.count": 3434,
              "stat.max": 1352,
              "stat.min": 4,
              "stat.p50": 11,
              "stat.p90": 24,
              "stat.p95": 30,
              "stat.p99": 65,
              "stat.p9990": 453,
              "stat.p9999": 1352,
              "stat.sum": 52215,
              "stat.avg": 15.205299941758883
            },
            "connections": {
              "gauge": 1.0
            }
          }
        }
      },
      "interpreter/io.buoyant.namerd.iface.NamerdInterpreterConfig": {
        "connect_latency_ms": {
          "stat.count": 0
        },
        "failed_connect_latency_ms": {
          "stat.count": 0
        },
        "sent_bytes": {
          "counter": 3447
        },
        "service_creation": {
          "service_acquisition_latency_ms": {
            "stat.count": 0
          }
        },
        "connection_received_bytes": {
          "stat.count": 0
        },
        "connection_duration": {
          "stat.count": 0
        },
        "connects": {
          "counter": 1
        },
        "success": {
          "counter": 4
        },
        "request_latency_ms": {
          "stat.count": 0
        },
        "mux": {
          "current_lease_ms": {
            "gauge": 9.223372E+12
          },
          "drained": {
            "counter": 0
          },
          "failuredetector": {
            "marked_busy": {
              "counter": 0
            },
            "ping_latency_us": {
              "stat.count": 12,
              "stat.max": 6843,
              "stat.min": 541,
              "stat.p50": 752,
              "stat.p90": 1299,
              "stat.p95": 1299,
              "stat.p99": 6843,
              "stat.p9990": 6843,
              "stat.p9999": 6843,
              "stat.sum": 16097,
              "stat.avg": 1341.4166666666667
            },
            "ping": {
              "counter": 226
            },
            "revivals": {
              "counter": 1
            },
            "close": {
              "counter": 0
            }
          },
          "leased": {
            "counter": 0
          },
          "draining": {
            "counter": 0
          }
        },
        "retries": {
          "stat.count": 0,
          "budget_exhausted": {
            "counter": 0
          }
        },
        "received_bytes": {
          "counter": 2413
        },
        "namer": {
          "nametreecache": {
            "evicts": {
              "counter": 0
            },
            "misses": {
              "counter": 1
            },
            "oneshots": {
              "counter": 0
            }
          },
          "dtabcache": {
            "evicts": {
              "counter": 0
            },
            "misses": {
              "counter": 1
            },
            "oneshots": {
              "counter": 0
            }
          },
          "namecache": {
            "evicts": {
              "counter": 0
            },
            "misses": {
              "counter": 1
            },
            "oneshots": {
              "counter": 0
            }
          },
          "bind_latency_us": {
            "stat.count": 0
          }
        },
        "bind": {
          "failures": {
            "counter": 0
          },
          "success": {
            "counter": 1
          },
          "requests": {
            "counter": 2
          }
        },
        "connection_sent_bytes": {
          "stat.count": 0
        },
        "connection_requests": {
          "stat.count": 0
        },
        "addrcache.size": {
          "gauge": 3.0
        },
        "socket_unwritable_ms": {
          "counter": 0
        },
        "closes": {
          "counter": 0
        },
        "addr": {
          "failures": {
            "counter": 0
          },
          "success": {
            "counter": 3
          },
          "requests": {
            "counter": 6
          }
        },
        "server": {},
        "available": {
          "gauge": 1.0
        },
        "singletonpool": {
          "connects": {
            "fail": {
              "counter": 0
            },
            "dead": {
              "counter": 0
            }
          }
        },
        "bindcache.size": {
          "gauge": 1.0
        },
        "request_payload_bytes": {
          "stat.count": 0
        },
        "socket_writable_ms": {
          "counter": 0
        },
        "cancelled_connects": {
          "counter": 0
        },
        "response_payload_bytes": {
          "stat.count": 0
        },
        "dtab": {
          "size": {
            "stat.count": 0
          }
        },
        "requests": {
          "counter": 4
        },
        "loadbalancer": {
          "size": {
            "gauge": 1.0
          },
          "rebuilds": {
            "counter": 228
          },
          "closed": {
            "gauge": 0.0
          },
          "load": {
            "gauge": 4.0
          },
          "meanweight": {
            "gauge": 1.0
          },
          "adds": {
            "counter": 1
          },
          "p2c": {
            "gauge": 1.0
          },
          "updates": {
            "counter": 226
          },
          "available": {
            "gauge": 1.0
          },
          "max_effort_exhausted": {
            "counter": 2
          },
          "busy": {
            "gauge": 0.0
          },
          "removes": {
            "counter": 0
          }
        },
        "pending": {
          "gauge": 4.0
        },
        "protocol": {
          "thriftmux": {
            "gauge": 1.0
          }
        },
        "connections": {
          "gauge": 1.0
        }
      }
    },
    "multiplier": {
      "service": {
        "svc": {
          "success": {
            "counter": 381
          },
          "request_latency_ms": {
            "stat.count": 33,
            "stat.max": 2,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 1,
            "stat.p99": 2,
            "stat.p9990": 2,
            "stat.p9999": 2,
            "stat.sum": 4,
            "stat.avg": 0.12121212121212122
          },
          "retries": {
            "per_request": {
              "stat.count": 33,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "total": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            }
          },
          "requests": {
            "counter": 381
          },
          "pending": {
            "gauge": 0.0
          }
        }
      },
      "client": {
        "$/inet/127.1/9030": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "failed_connect_latency_ms": {
            "stat.count": 0
          },
          "sent_bytes": {
            "counter": 4760
          },
          "service_creation": {
            "service_acquisition_latency_ms": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            }
          },
          "connection_received_bytes": {
            "stat.count": 0
          },
          "connection_duration": {
            "stat.count": 0
          },
          "failure_accrual": {
            "removals": {
              "counter": 0
            },
            "probes": {
              "counter": 0
            },
            "removed_for_ms": {
              "counter": 0
            },
            "revivals": {
              "counter": 0
            }
          },
          "connects": {
            "counter": 1
          },
          "pool_num_waited": {
            "counter": 0
          },
          "success": {
            "counter": 136
          },
          "service": {
            "svc": {
              "request_latency_ms": {
                "stat.count": 13,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "success": {
                "counter": 136
              },
              "pending": {
                "gauge": 0.0
              },
              "requests": {
                "counter": 136
              }
            }
          },
          "request_latency_ms": {
            "stat.count": 13,
            "stat.max": 0,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 0,
            "stat.p99": 0,
            "stat.p9990": 0,
            "stat.p9999": 0,
            "stat.sum": 0,
            "stat.avg": 0.0
          },
          "pool_waiters": {
            "gauge": 0.0
          },
          "retries": {
            "requeues_per_request": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "request_limit": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            },
            "cannot_retry": {
              "counter": 0
            },
            "not_open": {
              "counter": 0
            },
            "budget": {
              "gauge": 102.0
            },
            "requeues": {
              "counter": 0
            }
          },
          "received_bytes": {
            "counter": 3808
          },
          "connection_sent_bytes": {
            "stat.count": 0
          },
          "connection_requests": {
            "stat.count": 0
          },
          "pool_num_too_many_waiters": {
            "counter": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "pool_cached": {
            "gauge": 1.0
          },
          "pool_size": {
            "gauge": 0.0
          },
          "available": {
            "gauge": 1.0
          },
          "request_payload_bytes": {
            "stat.count": 13,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 455,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "cancelled_connects": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 13,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 364,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 136
          },
          "loadbalancer": {
            "size": {
              "gauge": 1.0
            },
            "rebuilds": {
              "counter": 1
            },
            "closed": {
              "gauge": 0.0
            },
            "load": {
              "gauge": 0.0
            },
            "meanweight": {
              "gauge": 1.0
            },
            "adds": {
              "counter": 1
            },
            "p2c": {
              "gauge": 1.0
            },
            "updates": {
              "counter": 1
            },
            "available": {
              "gauge": 1.0
            },
            "max_effort_exhausted": {
              "counter": 0
            },
            "busy": {
              "gauge": 0.0
            },
            "removes": {
              "counter": 0
            }
          },
          "pending": {
            "gauge": 0.0
          },
          "dispatcher": {
            "serial": {
              "queue_size": {
                "gauge": 0.0
              }
            }
          },
          "connections": {
            "gauge": 1.0
          }
        },
        "$/inet/127.1/9029": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "failed_connect_latency_ms": {
            "stat.count": 0
          },
          "sent_bytes": {
            "counter": 4165
          },
          "service_creation": {
            "service_acquisition_latency_ms": {
              "stat.count": 7,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            }
          },
          "connection_received_bytes": {
            "stat.count": 0
          },
          "connection_duration": {
            "stat.count": 0
          },
          "failure_accrual": {
            "removals": {
              "counter": 0
            },
            "probes": {
              "counter": 0
            },
            "removed_for_ms": {
              "counter": 0
            },
            "revivals": {
              "counter": 0
            }
          },
          "connects": {
            "counter": 1
          },
          "pool_num_waited": {
            "counter": 0
          },
          "success": {
            "counter": 119
          },
          "service": {
            "svc": {
              "request_latency_ms": {
                "stat.count": 7,
                "stat.max": 1,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 1,
                "stat.p99": 1,
                "stat.p9990": 1,
                "stat.p9999": 1,
                "stat.sum": 1,
                "stat.avg": 0.14285714285714285
              },
              "success": {
                "counter": 119
              },
              "pending": {
                "gauge": 0.0
              },
              "requests": {
                "counter": 119
              }
            }
          },
          "request_latency_ms": {
            "stat.count": 7,
            "stat.max": 1,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 1,
            "stat.p99": 1,
            "stat.p9990": 1,
            "stat.p9999": 1,
            "stat.sum": 1,
            "stat.avg": 0.14285714285714285
          },
          "pool_waiters": {
            "gauge": 0.0
          },
          "retries": {
            "requeues_per_request": {
              "stat.count": 7,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "request_limit": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            },
            "cannot_retry": {
              "counter": 0
            },
            "not_open": {
              "counter": 0
            },
            "budget": {
              "gauge": 102.0
            },
            "requeues": {
              "counter": 0
            }
          },
          "received_bytes": {
            "counter": 3332
          },
          "connection_sent_bytes": {
            "stat.count": 0
          },
          "connection_requests": {
            "stat.count": 0
          },
          "pool_num_too_many_waiters": {
            "counter": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "pool_cached": {
            "gauge": 1.0
          },
          "pool_size": {
            "gauge": 0.0
          },
          "available": {
            "gauge": 1.0
          },
          "request_payload_bytes": {
            "stat.count": 7,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 245,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "cancelled_connects": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 7,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 196,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 119
          },
          "loadbalancer": {
            "size": {
              "gauge": 1.0
            },
            "rebuilds": {
              "counter": 1
            },
            "closed": {
              "gauge": 0.0
            },
            "load": {
              "gauge": 0.0
            },
            "meanweight": {
              "gauge": 1.0
            },
            "adds": {
              "counter": 1
            },
            "p2c": {
              "gauge": 1.0
            },
            "updates": {
              "counter": 1
            },
            "available": {
              "gauge": 1.0
            },
            "max_effort_exhausted": {
              "counter": 0
            },
            "busy": {
              "gauge": 0.0
            },
            "removes": {
              "counter": 0
            }
          },
          "pending": {
            "gauge": 0.0
          },
          "dispatcher": {
            "serial": {
              "queue_size": {
                "gauge": 0.0
              }
            }
          },
          "connections": {
            "gauge": 1.0
          }
        },
        "$/inet/127.1/9092": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "failed_connect_latency_ms": {
            "stat.count": 0
          },
          "sent_bytes": {
            "counter": 4410
          },
          "service_creation": {
            "service_acquisition_latency_ms": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            }
          },
          "connection_received_bytes": {
            "stat.count": 0
          },
          "connection_duration": {
            "stat.count": 0
          },
          "failure_accrual": {
            "removals": {
              "counter": 0
            },
            "probes": {
              "counter": 0
            },
            "removed_for_ms": {
              "counter": 0
            },
            "revivals": {
              "counter": 0
            }
          },
          "connects": {
            "counter": 1
          },
          "pool_num_waited": {
            "counter": 0
          },
          "success": {
            "counter": 126
          },
          "service": {
            "svc": {
              "request_latency_ms": {
                "stat.count": 13,
                "stat.max": 1,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 1,
                "stat.p9990": 1,
                "stat.p9999": 1,
                "stat.sum": 1,
                "stat.avg": 0.07692307692307693
              },
              "success": {
                "counter": 126
              },
              "pending": {
                "gauge": 0.0
              },
              "requests": {
                "counter": 126
              }
            }
          },
          "request_latency_ms": {
            "stat.count": 13,
            "stat.max": 1,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 0,
            "stat.p99": 1,
            "stat.p9990": 1,
            "stat.p9999": 1,
            "stat.sum": 1,
            "stat.avg": 0.07692307692307693
          },
          "pool_waiters": {
            "gauge": 0.0
          },
          "retries": {
            "requeues_per_request": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "request_limit": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            },
            "cannot_retry": {
              "counter": 0
            },
            "not_open": {
              "counter": 0
            },
            "budget": {
              "gauge": 102.0
            },
            "requeues": {
              "counter": 0
            }
          },
          "received_bytes": {
            "counter": 3528
          },
          "connection_sent_bytes": {
            "stat.count": 0
          },
          "connection_requests": {
            "stat.count": 0
          },
          "pool_num_too_many_waiters": {
            "counter": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "pool_cached": {
            "gauge": 1.0
          },
          "pool_size": {
            "gauge": 0.0
          },
          "available": {
            "gauge": 1.0
          },
          "request_payload_bytes": {
            "stat.count": 13,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 455,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "cancelled_connects": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 13,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 364,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 126
          },
          "loadbalancer": {
            "size": {
              "gauge": 1.0
            },
            "rebuilds": {
              "counter": 1
            },
            "closed": {
              "gauge": 0.0
            },
            "load": {
              "gauge": 0.0
            },
            "meanweight": {
              "gauge": 1.0
            },
            "adds": {
              "counter": 1
            },
            "p2c": {
              "gauge": 1.0
            },
            "updates": {
              "counter": 1
            },
            "available": {
              "gauge": 1.0
            },
            "max_effort_exhausted": {
              "counter": 0
            },
            "busy": {
              "gauge": 0.0
            },
            "removes": {
              "counter": 0
            }
          },
          "pending": {
            "gauge": 0.0
          },
          "dispatcher": {
            "serial": {
              "queue_size": {
                "gauge": 0.0
              }
            }
          },
          "connections": {
            "gauge": 1.0
          }
        }
      },
      "bindcache": {
        "path": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 1
          },
          "oneshots": {
            "counter": 0
          }
        },
        "bound": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 3
          },
          "oneshots": {
            "counter": 0
          }
        },
        "tree": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 1
          },
          "oneshots": {
            "counter": 0
          }
        },
        "client": {
          "evicts": {
            "counter": 0
          },
          "misses": {
            "counter": 3
          },
          "oneshots": {
            "counter": 0
          }
        }
      },
      "server": {
        "0.0.0.0/4116": {
          "sent_bytes": {
            "counter": 10668
          },
          "connection_received_bytes": {
            "stat.count": 33,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 1155,
            "stat.avg": 35.0
          },
          "connection_duration": {
            "stat.count": 33,
            "stat.max": 3,
            "stat.min": 0,
            "stat.p50": 1,
            "stat.p90": 3,
            "stat.p95": 3,
            "stat.p99": 3,
            "stat.p9990": 3,
            "stat.p9999": 3,
            "stat.sum": 29,
            "stat.avg": 0.8787878787878788
          },
          "connects": {
            "counter": 381
          },
          "success": {
            "counter": 381
          },
          "request_latency_ms": {
            "stat.count": 33,
            "stat.max": 2,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 1,
            "stat.p99": 2,
            "stat.p9990": 2,
            "stat.p9999": 2,
            "stat.sum": 4,
            "stat.avg": 0.12121212121212122
          },
          "received_bytes": {
            "counter": 13335
          },
          "read_timeout": {
            "counter": 0
          },
          "write_timeout": {
            "counter": 0
          },
          "connection_sent_bytes": {
            "stat.count": 33,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 924,
            "stat.avg": 28.0
          },
          "connection_requests": {
            "stat.count": 33,
            "stat.max": 1,
            "stat.min": 1,
            "stat.p50": 1,
            "stat.p90": 1,
            "stat.p95": 1,
            "stat.p99": 1,
            "stat.p9990": 1,
            "stat.p9999": 1,
            "stat.sum": 33,
            "stat.avg": 1.0
          },
          "transit_latency_ms": {
            "stat.count": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "request_payload_bytes": {
            "stat.count": 33,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 1155,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 33,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 924,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 381
          },
          "pending": {
            "gauge": 0.0
          },
          "handletime_us": {
            "stat.count": 33,
            "stat.max": 27,
            "stat.min": 7,
            "stat.p50": 11,
            "stat.p90": 20,
            "stat.p95": 20,
            "stat.p99": 27,
            "stat.p9990": 27,
            "stat.p9999": 27,
            "stat.sum": 407,
            "stat.avg": 12.333333333333334
          },
          "connections": {
            "gauge": 0.0
          }
        }
      }
    },
    "to_be_expired": {
      "client": {
        "$/inet/127.1/9030": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "failed_connect_latency_ms": {
            "stat.count": 0
          },
          "sent_bytes": {
            "counter": 4760
          },
          "service_creation": {
            "service_acquisition_latency_ms": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            }
          },
          "connection_received_bytes": {
            "stat.count": 0
          },
          "connection_duration": {
            "stat.count": 0
          },
          "failure_accrual": {
            "removals": {
              "counter": 0
            },
            "probes": {
              "counter": 0
            },
            "removed_for_ms": {
              "counter": 0
            },
            "revivals": {
              "counter": 0
            }
          },
          "connects": {
            "counter": 1
          },
          "pool_num_waited": {
            "counter": 0
          },
          "success": {
            "counter": 136
          },
          "service": {
            "svc": {
              "request_latency_ms": {
                "stat.count": 13,
                "stat.max": 0,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 0,
                "stat.p9990": 0,
                "stat.p9999": 0,
                "stat.sum": 0,
                "stat.avg": 0.0
              },
              "success": {
                "counter": 136
              },
              "pending": {
                "gauge": 0.0
              },
              "requests": {
                "counter": 136
              }
            }
          },
          "request_latency_ms": {
            "stat.count": 13,
            "stat.max": 0,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 0,
            "stat.p99": 0,
            "stat.p9990": 0,
            "stat.p9999": 0,
            "stat.sum": 0,
            "stat.avg": 0.0
          },
          "pool_waiters": {
            "gauge": 0.0
          },
          "retries": {
            "requeues_per_request": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "request_limit": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            },
            "cannot_retry": {
              "counter": 0
            },
            "not_open": {
              "counter": 0
            },
            "budget": {
              "gauge": 102.0
            },
            "requeues": {
              "counter": 0
            }
          },
          "received_bytes": {
            "counter": 3808
          },
          "connection_sent_bytes": {
            "stat.count": 0
          },
          "connection_requests": {
            "stat.count": 0
          },
          "pool_num_too_many_waiters": {
            "counter": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "pool_cached": {
            "gauge": 1.0
          },
          "pool_size": {
            "gauge": 0.0
          },
          "available": {
            "gauge": 1.0
          },
          "request_payload_bytes": {
            "stat.count": 13,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 455,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "cancelled_connects": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 13,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 364,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 136
          },
          "loadbalancer": {
            "size": {
              "gauge": 1.0
            },
            "rebuilds": {
              "counter": 1
            },
            "closed": {
              "gauge": 0.0
            },
            "load": {
              "gauge": 0.0
            },
            "meanweight": {
              "gauge": 1.0
            },
            "adds": {
              "counter": 1
            },
            "p2c": {
              "gauge": 1.0
            },
            "updates": {
              "counter": 1
            },
            "available": {
              "gauge": 1.0
            },
            "max_effort_exhausted": {
              "counter": 0
            },
            "busy": {
              "gauge": 0.0
            },
            "removes": {
              "counter": 0
            }
          },
          "pending": {
            "gauge": 0.0
          },
          "dispatcher": {
            "serial": {
              "queue_size": {
                "gauge": 0.0
              }
            }
          },
          "connections": {
            "gauge": 1.0
          }
        },
        "$/inet/127.1/9029": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "failed_connect_latency_ms": {
            "stat.count": 0
          },
          "sent_bytes": {
            "counter": 4165
          },
          "service_creation": {
            "service_acquisition_latency_ms": {
              "stat.count": 7,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            }
          },
          "connection_received_bytes": {
            "stat.count": 0
          },
          "connection_duration": {
            "stat.count": 0
          },
          "failure_accrual": {
            "removals": {
              "counter": 0
            },
            "probes": {
              "counter": 0
            },
            "removed_for_ms": {
              "counter": 0
            },
            "revivals": {
              "counter": 0
            }
          },
          "connects": {
            "counter": 1
          },
          "pool_num_waited": {
            "counter": 0
          },
          "success": {
            "counter": 119
          },
          "service": {
            "svc": {
              "request_latency_ms": {
                "stat.count": 7,
                "stat.max": 1,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 1,
                "stat.p99": 1,
                "stat.p9990": 1,
                "stat.p9999": 1,
                "stat.sum": 1,
                "stat.avg": 0.14285714285714285
              },
              "success": {
                "counter": 119
              },
              "pending": {
                "gauge": 0.0
              },
              "requests": {
                "counter": 119
              }
            }
          },
          "request_latency_ms": {
            "stat.count": 7,
            "stat.max": 1,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 1,
            "stat.p99": 1,
            "stat.p9990": 1,
            "stat.p9999": 1,
            "stat.sum": 1,
            "stat.avg": 0.14285714285714285
          },
          "pool_waiters": {
            "gauge": 0.0
          },
          "retries": {
            "requeues_per_request": {
              "stat.count": 7,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "request_limit": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            },
            "cannot_retry": {
              "counter": 0
            },
            "not_open": {
              "counter": 0
            },
            "budget": {
              "gauge": 102.0
            },
            "requeues": {
              "counter": 0
            }
          },
          "received_bytes": {
            "counter": 3332
          },
          "connection_sent_bytes": {
            "stat.count": 0
          },
          "connection_requests": {
            "stat.count": 0
          },
          "pool_num_too_many_waiters": {
            "counter": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "pool_cached": {
            "gauge": 1.0
          },
          "pool_size": {
            "gauge": 0.0
          },
          "available": {
            "gauge": 1.0
          },
          "request_payload_bytes": {
            "stat.count": 7,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 245,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "cancelled_connects": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 7,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 196,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 119
          },
          "loadbalancer": {
            "size": {
              "gauge": 1.0
            },
            "rebuilds": {
              "counter": 1
            },
            "closed": {
              "gauge": 0.0
            },
            "load": {
              "gauge": 0.0
            },
            "meanweight": {
              "gauge": 1.0
            },
            "adds": {
              "counter": 1
            },
            "p2c": {
              "gauge": 1.0
            },
            "updates": {
              "counter": 1
            },
            "available": {
              "gauge": 1.0
            },
            "max_effort_exhausted": {
              "counter": 0
            },
            "busy": {
              "gauge": 0.0
            },
            "removes": {
              "counter": 0
            }
          },
          "pending": {
            "gauge": 0.0
          },
          "dispatcher": {
            "serial": {
              "queue_size": {
                "gauge": 0.0
              }
            }
          },
          "connections": {
            "gauge": 1.0
          }
        },
        "$/inet/127.1/9092": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "failed_connect_latency_ms": {
            "stat.count": 0
          },
          "sent_bytes": {
            "counter": 4410
          },
          "service_creation": {
            "service_acquisition_latency_ms": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            }
          },
          "connection_received_bytes": {
            "stat.count": 0
          },
          "connection_duration": {
            "stat.count": 0
          },
          "failure_accrual": {
            "removals": {
              "counter": 0
            },
            "probes": {
              "counter": 0
            },
            "removed_for_ms": {
              "counter": 0
            },
            "revivals": {
              "counter": 0
            }
          },
          "connects": {
            "counter": 1
          },
          "pool_num_waited": {
            "counter": 0
          },
          "success": {
            "counter": 126
          },
          "service": {
            "svc": {
              "request_latency_ms": {
                "stat.count": 13,
                "stat.max": 1,
                "stat.min": 0,
                "stat.p50": 0,
                "stat.p90": 0,
                "stat.p95": 0,
                "stat.p99": 1,
                "stat.p9990": 1,
                "stat.p9999": 1,
                "stat.sum": 1,
                "stat.avg": 0.07692307692307693
              },
              "success": {
                "counter": 126
              },
              "pending": {
                "gauge": 0.0
              },
              "requests": {
                "counter": 126
              }
            }
          },
          "request_latency_ms": {
            "stat.count": 13,
            "stat.max": 1,
            "stat.min": 0,
            "stat.p50": 0,
            "stat.p90": 0,
            "stat.p95": 0,
            "stat.p99": 1,
            "stat.p9990": 1,
            "stat.p9999": 1,
            "stat.sum": 1,
            "stat.avg": 0.07692307692307693
          },
          "pool_waiters": {
            "gauge": 0.0
          },
          "retries": {
            "requeues_per_request": {
              "stat.count": 13,
              "stat.max": 0,
              "stat.min": 0,
              "stat.p50": 0,
              "stat.p90": 0,
              "stat.p95": 0,
              "stat.p99": 0,
              "stat.p9990": 0,
              "stat.p9999": 0,
              "stat.sum": 0,
              "stat.avg": 0.0
            },
            "request_limit": {
              "counter": 0
            },
            "budget_exhausted": {
              "counter": 0
            },
            "cannot_retry": {
              "counter": 0
            },
            "not_open": {
              "counter": 0
            },
            "budget": {
              "gauge": 102.0
            },
            "requeues": {
              "counter": 0
            }
          },
          "received_bytes": {
            "counter": 3528
          },
          "connection_sent_bytes": {
            "stat.count": 0
          },
          "connection_requests": {
            "stat.count": 0
          },
          "pool_num_too_many_waiters": {
            "counter": 0
          },
          "socket_unwritable_ms": {
            "counter": 0
          },
          "closes": {
            "counter": 0
          },
          "pool_cached": {
            "gauge": 1.0
          },
          "pool_size": {
            "gauge": 0.0
          },
          "available": {
            "gauge": 1.0
          },
          "request_payload_bytes": {
            "stat.count": 13,
            "stat.max": 35,
            "stat.min": 35,
            "stat.p50": 35,
            "stat.p90": 35,
            "stat.p95": 35,
            "stat.p99": 35,
            "stat.p9990": 35,
            "stat.p9999": 35,
            "stat.sum": 455,
            "stat.avg": 35.0
          },
          "socket_writable_ms": {
            "counter": 0
          },
          "cancelled_connects": {
            "counter": 0
          },
          "response_payload_bytes": {
            "stat.count": 13,
            "stat.max": 28,
            "stat.min": 28,
            "stat.p50": 28,
            "stat.p90": 28,
            "stat.p95": 28,
            "stat.p99": 28,
            "stat.p9990": 28,
            "stat.p9999": 28,
            "stat.sum": 364,
            "stat.avg": 28.0
          },
          "dtab": {
            "size": {
              "stat.count": 0
            }
          },
          "requests": {
            "counter": 126
          },
          "loadbalancer": {
            "size": {
              "gauge": 1.0
            },
            "rebuilds": {
              "counter": 1
            },
            "closed": {
              "gauge": 0.0
            },
            "load": {
              "gauge": 0.0
            },
            "meanweight": {
              "gauge": 1.0
            },
            "adds": {
              "counter": 1
            },
            "p2c": {
              "gauge": 1.0
            },
            "updates": {
              "counter": 1
            },
            "available": {
              "gauge": 1.0
            },
            "max_effort_exhausted": {
              "counter": 0
            },
            "busy": {
              "gauge": 0.0
            },
            "removes": {
              "counter": 0
            }
          },
          "pending": {
            "gauge": 0.0
          },
          "dispatcher": {
            "serial": {
              "queue_size": {
                "gauge": 0.0
              }
            }
          },
          "connections": {
            "gauge": 1.0
          }
        }
      }
    },
    "lots_of_clients": {
      "client": {
        "$/inet/127.1/1111": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        },
        "$/inet/127.1/2222": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        },
        "$/inet/127.1/3333": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        },
        "$/inet/127.1/4444": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        },
        "$/inet/127.1/5555": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        },
        "$/inet/127.1/6666": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        },
        "$/inet/127.1/7777": {
          "connect_latency_ms": {
            "stat.count": 0
          }
        }
      }
    },
    "service_test": {
      "service": {
        "service_1": {
          "requests": {
            "counter": 33
          }
        },
        "service_2": {
          "requests": {
            "counter": 23
          }
        }
      },
      "client": {
        "$/inet/127.1/1111": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "service": {
            "service_1": { "requests": { "counter": 3 }}
          }
        },
        "$/inet/127.1/2222": {
          "connect_latency_ms": {
          },
          "service": {
            "service_1": { "requests": { "counter": 3 }}
          }
        },
        "$/inet/127.1/3333": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "service": {
            "service_1": { "requests": { "counter": 3 }}
          }
        },
        "$/inet/127.1/4444": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "service": {
            "service_1": { "requests": { "counter": 3 }}
          }
        },
        "$/inet/127.1/5555": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "service": {
            "service_1": { "requests": { "counter": 3 }},
            "service_2": { "requests": { "counter": 3 }}
          }
        },
        "$/inet/127.1/6666": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "service": {
            "service_2": { "requests": { "counter": 3 }}
          }
        },
        "$/inet/127.1/7777": {
          "connect_latency_ms": {
            "stat.count": 0
          },
          "service": {
            "service_2": { "requests": { "counter": 3 }}
          }
        }
      }
    },
    "inet": {
      "dns": {
        "queue_size": {
          "gauge": 0.0
        },
        "successes": {
          "counter": 166676
        },
        "cache": {
          "evicts": {
            "gauge": 0.0
          },
          "size": {
            "gauge": 0.0
          },
          "hit_rate": {
            "gauge": 1.0
          }
        },
        "dns_lookups": {
          "counter": 165653
        },
        "failures": {
          "counter": 0
        },
        "dns_lookup_failures": {
          "counter": 0
        },
        "lookup_ms": {
          "stat.count": 1792,
          "stat.max": 23,
          "stat.min": 0,
          "stat.p50": 1,
          "stat.p90": 2,
          "stat.p95": 2,
          "stat.p99": 4,
          "stat.p9990": 21,
          "stat.p9999": 23,
          "stat.sum": 1745,
          "stat.avg": 0.9737723214285714
        }
      }
    },
    "toggles": {
      "com.twitter.finagle.mux": {
        "checksum": {
          "gauge": 1.65079066E+9
        }
      },
      "com.twitter.finagle.thrift": {
        "checksum": {
          "gauge": 1.14950771E+9
        }
      }
    }
  }
}});
