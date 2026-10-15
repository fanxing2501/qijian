package htpc.client

import org.scalajs.dom

object Main:
  def main(args: Array[String]): Unit =
    if dom.window.location.pathname == "/console" then Console.mount()
    else htpc.client.tv.TvShell.mount()
