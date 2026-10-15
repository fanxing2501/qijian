package htpc.client.tv

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global

object FeedRow:
  def render(feed: FeedItem, context: PageContext): HtmlElement =
    val cards = Var(Vector.empty[FeedCard])
    val loading = Var(false)
    val problem = Var(Option.empty[String])
    val finished = Var(false)
    var cursor: Option[Cursor] = None
    var session: Option[FeedSession[FeedCard]] = None
    var disposed = false
    var observer: Option[dom.IntersectionObserver] = None

    def load(): Unit = if !loading.now() && !finished.now() && !disposed then
      loading.set(true); problem.set(None)
      val source = session.getOrElse { val s = feed.source.open(); session = Some(s); s }
      source.load(cursor, 12).recover { case scala.util.control.NonFatal(error) => Left(FeedError(error.getMessage)) }.foreach { result => if !disposed then
        loading.set(false)
        result match
          case Left(error) => problem.set(Some(error.message))
          case Right(batch) =>
            cards.update(old => (old ++ batch.items).distinctBy(_.id))
            cursor = batch.nextCursor
            finished.set(cursor.isEmpty)
            if cursor.nonEmpty && context.focus.awaiting(feed.id.value + ":").exists { id =>
              id != s"${feed.id.value}:more" && !cards.now().exists(card => id == s"${feed.id.value}:${card.id.value}")
            } then load()
      }

    sectionTag(cls := s"tv-feed ${feed.layout.toString.toLowerCase}",
      htmlAttr("data-feed-id", StringAsIsCodec) := feed.id.value,
      onMountCallback { ctx =>
        val o = new dom.IntersectionObserver((entries, _) => {
          if entries.exists(_.isIntersecting) then { load(); observer.foreach(_.disconnect()) }
        }, new dom.IntersectionObserverInit { rootMargin = "200px" })
        observer = Some(o); o.observe(ctx.thisNode.ref)
      },
      onUnmountCallback(_ => { disposed = true; observer.foreach(_.disconnect()); session.foreach(_.close()) }),
      div(cls := "tv-feed-heading", div(h2(feed.title), p(feed.subtitle)),
        span(cls := "tv-feed-count", child.text <-- cards.signal.combineWith(finished.signal).map((items, end) =>
          if items.isEmpty then "" else if end then s"${items.size} 项" else s"${items.size}+ 项"))),
      div(cls := "tv-card-track",
        children <-- cards.signal.split(_.id) { (id, initial, _) =>
          button(cls := "tv-card", context.focus.target(s"${feed.id.value}:${id.value}", feed.id.value),
            div(cls := "tv-card-art", img(src := initial.artwork.url, alt := initial.artwork.alt, htmlAttr("loading", StringAsIsCodec) := "lazy",
              onError --> { event => initial.artwork.fallback.foreach { fallback =>
                val image = event.target.asInstanceOf[dom.HTMLImageElement]
                image.classList.add("is-fallback")
                if !image.src.endsWith(fallback) then image.src = fallback
              }}),
              initial.badge.map(b => span(cls := "tv-card-badge", b))),
            strong(initial.title), span(cls := "tv-card-subtitle", initial.subtitle),
            onClick --> (_ => context.navigation.go(initial.destination)))
        },
        child <-- loading.signal.combineWith(cards.signal).map { (busy, values) =>
          if busy && values.isEmpty then div(cls := "tv-skeletons", (1 to 4).map(_ => div(cls := "tv-skeleton")))
          else if busy then div(cls := "tv-loading-card", "正在加载…") else div()
        },
        child <-- finished.signal.combineWith(cards.signal, loading.signal, problem.signal).map { (end, values, busy, error) =>
          if !end && !busy && error.isEmpty && session.nonEmpty then
            button(cls := "tv-more", context.focus.target(s"${feed.id.value}:more", feed.id.value), Icon("arrow"), "浏览更多", onClick --> (_ => load()))
          else if values.isEmpty && end then div(cls := "tv-feed-empty", "这里暂时没有内容。")
          else div()
        }
      ),
      child <-- problem.signal.map {
        case Some(message) => div(cls := "tv-feed-error", span(message),
          button(cls := "tv-button subtle", context.focus.target(s"${feed.id.value}:retry", feed.id.value), "重新加载", onClick --> (_ => load())))
        case None => div()
      }
    )
