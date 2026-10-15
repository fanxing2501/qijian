package htpc.client.tv

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js

@js.native private trait PadButton extends js.Object:
  val pressed: Boolean = js.native
@js.native private trait Pad extends js.Object:
  val connected: Boolean = js.native
  val mapping: String = js.native
  val axes: js.Array[Double] = js.native
  val buttons: js.Array[PadButton] = js.native
@js.native private trait PadNavigator extends js.Object:
  def getGamepads(): js.Array[Pad] = js.native

/** Standard Gamepad mapping: D-pad/left stick, south=A, east=B.
 * Edges prevent repeated confirmation; held directions repeat after 380ms. */
final class GamepadInput(focus: FocusManager, home: () => Unit, enabled: () => Boolean = () => true):
  val connected = Var(false)
  private var frame = 0
  private var running = false
  private var previous = Set.empty[String]
  private var direction: Option[String] = None
  private var nextRepeat = 0.0
  val available = js.typeOf(dom.window.navigator.asInstanceOf[js.Dynamic].getGamepads) == "function"

  private def tick(time: Double): Unit = if running then
    val pads = if available then scala.util.Try(dom.window.navigator.asInstanceOf[PadNavigator].getGamepads().toVector.filter(p => p != null && p.connected && p.mapping == "standard")).getOrElse(Vector.empty) else Vector.empty
    if connected.now() != pads.nonEmpty then connected.set(pads.nonEmpty)
    val pad = pads.headOption
    val pressed = pad.fold(Set.empty[String]) { p =>
      def button(n: Int) = n < p.buttons.length && p.buttons(n).pressed
      val x = if p.axes.length > 0 then p.axes(0) else 0.0
      val y = if p.axes.length > 1 then p.axes(1) else 0.0
      Vector("ArrowUp" -> (button(12) || y < -0.55), "ArrowDown" -> (button(13) || y > 0.55),
        "ArrowLeft" -> (button(14) || x < -0.55), "ArrowRight" -> (button(15) || x > 0.55),
        "confirm" -> button(0), "back" -> button(1), "home" -> button(9)).collect { case (key, true) => key }.toSet
    }
    if enabled() && dom.document.visibilityState == "visible" && dom.document.hasFocus() then
      val fresh = pressed -- previous
      if fresh.contains("back") then focus.cancel()
      else if fresh.contains("home") then home()
      else if fresh.contains("confirm") then focus.confirm()
      val next = Vector("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight").find(pressed.contains)
      if next != direction then
        next.foreach(focus.navigate)
        direction = next
        nextRepeat = time + 380
      else if next.nonEmpty && time >= nextRepeat then
        next.foreach(focus.navigate)
        nextRepeat = time + 125
    else direction = None
    previous = pressed
    frame = dom.window.requestAnimationFrame(t => tick(t))

  def start(): Unit = if !running then { running = true; frame = dom.window.requestAnimationFrame(t => tick(t)) }
  def stop(): Unit = { running = false; dom.window.cancelAnimationFrame(frame); previous = Set.empty; direction = None }
