package io.buoyant.linkerd

import cats.data.{NonEmptyList, ValidatedNel}

package object config {
  type ValidatedConfig[A] = ValidatedNel[ConfigError, A]
  def invalid[A](e: ConfigError): ValidatedConfig[A] = cats.data.Validated.invalidNel(e)
  def invalid[A](es: NonEmptyList[ConfigError]): ValidatedConfig[A] = cats.data.Validated.invalid(es)
  def valid[A](a: A): ValidatedConfig[A] = cats.data.Validated.valid(a).toValidatedNel
}
