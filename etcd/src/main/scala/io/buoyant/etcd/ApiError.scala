package io.buoyant.etcd

case class ApiError(
  errorCode: Int,
  message: String,
  cause: String,
  index: Int
) extends Exception(message)

// see: https://github.com/coreos/etcd/blob/master/Documentation/v2/errorcode.md
object ApiError {

  class ErrorGroup(val codes: Int*) {
    def unapply(code: Int): Option[Int] =
      if (codes contains code) Some(code) else None
  }

  val KeyNotFound = 100
  val TestFailed = 101
  val NotFile = 102
  val NotDir = 104
  val NodeExist = 105
  val RootReadOnly = 107
  val DirNotEmpty = 108
  object Command extends ErrorGroup(
    KeyNotFound,
    TestFailed,
    NotFile,
    NotDir,
    NodeExist,
    RootReadOnly,
    DirNotEmpty
  )

  val PrevValueRequired = 201
  val TtlNan = 202
  val IndexNan = 203
  val InvalidField = 209
  val InvalidForm = 210
  object Content extends ErrorGroup(
    PrevValueRequired,
    TtlNan,
    IndexNan,
    InvalidField,
    InvalidForm
  )

  val RaftInternal = 300
  val LeaderElect = 301
  object Raft extends ErrorGroup(
    RaftInternal,
    LeaderElect
  )

  val WatcherCleared = 400
  val EventIndexCleared = 401
  object Etcd extends ErrorGroup(
    WatcherCleared,
    EventIndexCleared
  )

  object Known extends ErrorGroup(Command.codes ++ Content.codes ++ Raft.codes ++ Etcd.codes: _*)
}

