package htpc.server

import cats.effect.IO
import htpc.protocol.*

object QrCode:
  def render(text: String): IO[Either[SystemError, Screenshot]] = IO {
    require(text.nonEmpty && text.length <= 2000)
    val matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 1, 1)
    val paths = (for y <- 0 until matrix.getHeight; x <- 0 until matrix.getWidth if matrix.get(x, y) yield s"M$x ${y}h1v1h-1z").mkString
    val svg = s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.getWidth} ${matrix.getHeight}" shape-rendering="crispEdges"><path fill="white" d="M0 0h${matrix.getWidth}v${matrix.getHeight}H0z"/><path fill="black" d="$paths"/></svg>"""
    Screenshot("image/svg+xml", java.util.Base64.getEncoder.encodeToString(svg.getBytes("UTF-8")))
  }.attempt.map(_.left.map(_ => SystemError(ErrorCode.InvalidArgument, "Text cannot be encoded as a QR code")))
