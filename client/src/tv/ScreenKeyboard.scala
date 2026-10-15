package htpc.client.tv

import com.raquo.laminar.api.L.*

object ScreenKeyboard:
  def render(value: Var[String], context: PageContext, submit: () => Unit): HtmlElement =
    val upper = Var(false)
    div(cls := "tv-screen-keyboard",
      Vector("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm").zipWithIndex.map { (letters, row) =>
        div(cls := "tv-key-row", letters.toVector.map { letter => button(cls := "tv-key",
          context.focus.target(s"keyboard:$letter", s"keyboard:$row"),
          child.text <-- upper.signal.map(shift => if shift then letter.toUpper.toString else letter.toString),
          onClick --> (_ => value.update(_ + (if upper.now() then letter.toUpper else letter)))) })
      },
      div(cls := "tv-key-row tv-key-actions",
        button(cls := "tv-key", context.focus.target("keyboard:shift", "keyboard:4"), "大小写", onClick --> (_ => upper.update(!_))),
        button(cls := "tv-key", context.focus.target("keyboard:space", "keyboard:4"), "空格", onClick --> (_ => value.update(_ + " "))),
        button(cls := "tv-key", context.focus.target("keyboard:delete", "keyboard:4"), "⌫ 删除", onClick --> (_ => value.update(_.dropRight(1)))),
        button(cls := "tv-key", context.focus.target("keyboard:clear", "keyboard:4"), "清空", onClick --> (_ => value.set(""))),
        button(cls := "tv-key primary", context.focus.target("keyboard:search", "keyboard:4"), "搜索", onClick --> (_ => submit())))
    )
