package io.github.windymelt.cdpscala.util

object Base64:
  def apply(arr: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(arr)
  def unapply(s: String): Option[Array[Byte]] = Some(
    java.util.Base64.getDecoder.decode(s)
  )
