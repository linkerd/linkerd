package com.twitter.finagle.buoyant

import com.twitter.finagle.http.filter.StatsFilter
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}

object HttpStatusCodeFilter {
  val role = Stack.Role("HttpStatusCodeFilter")

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Stats, ServiceFactory[Request, Response]] {
      val role = HttpStatusCodeFilter.role
      val description = "Records HTTP status code stats"

      def make(_stats: param.Stats, next: ServiceFactory[Request, Response]) = {
        val param.Stats(statsReceiver) = _stats
        if (statsReceiver.isNull) next
        else new StatsFilter[Request](statsReceiver) andThen next
      }
    }
}
