package htpc.youtube

import io.circe.{Codec, Decoder, Encoder}

final case class VideoId(value: String)
object VideoId:
  given Codec[VideoId] = Codec.from(
    Decoder.decodeString.emap(s => Either.cond(s.matches("[A-Za-z0-9_-]{11}"), VideoId(s), "Invalid YouTube video ID")),
    Encoder.encodeString.contramap(_.value)
  )

final case class YouTubeVideo(id: VideoId, title: String, author: String, thumbnail: String, duration: Option[String], views: Option[String]) derives Codec.AsObject
final case class YouTubeSnapshot(url: String, title: String, ready: Boolean, signedIn: Boolean,
  accountName: Option[String], avatar: Option[String], videos: Vector[YouTubeVideo], hasMore: Boolean, notice: Option[String]) derives Codec.AsObject

/** Executed in an owned browser tab. All site-specific extraction lives in the
 * frontend/shared module; the server remains a generic CDP capability service. */
object YouTubeScripts:
  val snapshot: String = """(() => {
    const text = x => typeof x === 'string' ? x : (x?.simpleText || x?.content || x?.runs?.map(r => r.text || '').join('') || '');
    const thumbnail = x => {
      const items = x?.thumbnails || x?.sources || [];
      const image = items[items.length - 1];
      return image?.url || '';
    };
    const videos = new Map();
    const add = (id, title, author, image, duration, views) => {
      if (!/^[\w-]{11}$/.test(id || '') || !title || videos.has(id)) return;
      videos.set(id, { id, title, author: author || 'YouTube',
        thumbnail: /^https:\/\//.test(image || '') ? image : `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
        duration: duration || null, views: views || null });
    };
    const visited = new WeakSet();
    let remaining = 25000, hasMore = false, notice = null;
    function walk(value, depth = 0) {
      if (!value || typeof value !== 'object' || depth > 35 || --remaining < 0 || visited.has(value)) return;
      visited.add(value);
      if (value.continuationItemRenderer) hasMore = true;
      for (const key of ['videoRenderer', 'gridVideoRenderer', 'playlistVideoRenderer']) {
        const v = value[key];
        if (v) add(v.videoId, text(v.title), text(v.ownerText || v.shortBylineText || v.longBylineText),
          thumbnail(v.thumbnail), text(v.lengthText), text(v.shortViewCountText || v.viewCountText));
      }
      const l = value.lockupViewModel;
      if (l && l.contentType === 'LOCKUP_CONTENT_TYPE_VIDEO') {
        const meta = l.metadata?.lockupMetadataViewModel;
        const rows = meta?.metadata?.contentMetadataViewModel?.metadataRows || [];
        const parts = rows.flatMap(row => row.metadataParts || []).map(part => text(part.text));
        const image = l.contentImage?.thumbnailViewModel;
        let duration = null;
        for (const overlay of image?.overlays || []) {
          const badges = overlay.thumbnailOverlayBadgeViewModel?.thumbnailBadges || [];
          for (const b of badges) duration ||= text(b.thumbnailBadgeViewModel?.text);
        }
        add(l.contentId, text(meta?.title), parts[0], thumbnail(image?.image), duration, parts[1]);
      }
      if (value.messageRenderer && !notice) notice = text(value.messageRenderer.text);
      for (const [key, child] of Object.entries(value)) {
        if (!['adSlotRenderer','displayAdRenderer','promotedSparklesWebRenderer'].includes(key)) walk(child, depth + 1);
      }
    }
    walk(window.ytInitialData);
    const rendered = document.querySelectorAll('ytd-video-renderer,ytd-grid-video-renderer,ytd-rich-item-renderer,yt-lockup-view-model,ytd-playlist-video-renderer');
    for (const el of rendered) {
      walk(el.data || el.__data?.data);
      const link = el.querySelector('a[href*="/watch?v="]');
      if (!link) continue;
      const id = new URL(link.href, location.href).searchParams.get('v');
      const title = el.querySelector('#video-title, .yt-lockup-metadata-view-model__title')?.textContent?.trim() || link.getAttribute('title');
      const author = el.querySelector('#channel-name, .yt-content-metadata-view-model__metadata-text')?.textContent?.trim();
      const duration = el.querySelector('ytd-thumbnail-overlay-time-status-renderer, .yt-badge-shape__text')?.textContent?.trim();
      add(id, title, author, el.querySelector('img')?.src, duration, null);
    }
    const continuation = !!document.querySelector('ytd-continuation-item-renderer');
    hasMore = rendered.length ? continuation : hasMore || continuation;
    const avatar = document.querySelector('#avatar-btn img');
    const signedIn = window.ytcfg?.get('LOGGED_IN') === true;
    return {
      url: location.href, title: document.title,
      ready: !!window.ytInitialData && typeof window.ytcfg?.get('LOGGED_IN') === 'boolean', signedIn,
      accountName: signedIn ? (window.ytcfg?.get('ACCOUNT_NAME') || null) : null,
      avatar: signedIn ? (avatar?.src || null) : null,
      videos: [...videos.values()].slice(0, 200), hasMore, notice
    };
  })()"""

  val scrollMore: String = """(() => {
    const continuation = document.querySelector('ytd-continuation-item-renderer');
    if (continuation) continuation.scrollIntoView({block:'end'});
    else window.scrollTo(0, document.documentElement.scrollHeight);
    return true;
  })()"""
