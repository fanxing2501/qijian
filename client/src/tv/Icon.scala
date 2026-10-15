package htpc.client.tv

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec

/** Monochrome SVG masks inherit their component's color and stay crisp on a TV. */
object Icon:
  def apply(name: String): HtmlElement = span(cls := s"tv-icon icon-$name", htmlAttr("aria-hidden", StringAsIsCodec) := "true")
