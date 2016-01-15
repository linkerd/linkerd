#@namespace scala io.buoyant.router.thriftscala

service PingService {
  string ping(
    1: string msg
  )
}
