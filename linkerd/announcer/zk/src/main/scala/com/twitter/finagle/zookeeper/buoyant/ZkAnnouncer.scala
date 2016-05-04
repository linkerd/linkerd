package com.twitter.finagle.zookeeper.buoyant

import com.twitter.finagle.zookeeper.{ZkAnnouncer => FZkAnnouncer, DefaultZkClientFactory}

class ZkAnnouncer extends FZkAnnouncer(DefaultZkClientFactory)
