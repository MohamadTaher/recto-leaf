# Teaching a site its comments

All site knowledge lives in the extension. The app asks `NovelCommentSource` and draws what comes
back; it has no list of sites, no endpoint templates and no per-site parsing. So adding comments to
a site is one interface on one extension class.

## The minimum

```kotlin
class SomeSite : NovelHttpSource(), NovelCommentSource {

    override val commentCapabilities = NovelCommentCapabilities()

    override suspend fun getComments(request: NovelCommentRequest): NovelCommentPage {
        val url = "$baseUrl${request.target.chapter?.url}/comments"
        val document = client.newCall(GET(url, headers)).awaitSuccess().use { it.asJsoup() }

        return NovelCommentPage(
            comments = document.select(".comment").map { element ->
                NovelComment(
                    id = element.attr("data-id"),
                    body = element.selectFirst(".comment-body")?.html().orEmpty(),
                    author = element.selectFirst(".comment-author")?.text().orEmpty(),
                )
            },
            hasNextPage = false,
        )
    }
}
```

That is a complete, working implementation. The default `NovelCommentCapabilities()` says: chapter
comments only, flat, unsorted, unscored, read-only, no avatars — and the sheet then draws exactly
that, with no sort control, no vote arrows and no reply box. Nothing has to be stubbed.

## Growing it

Everything else is a field on the capabilities plus the code to back it. Claim a capability only
once the code behind it works; the sheet reads the capability, not the method.

| Want | Set | Then |
|---|---|---|
| Sorting | `sorts = listOf(NovelCommentSort("new", "Newest"), …)` | read `request.sort.key` |
| The novel's own page too | `scopes = setOf(NOVEL, CHAPTER)` | branch on `request.target.chapter == null` |
| Threads | `maxDepth = 2`, or `UNLIMITED` | set `parentId`, or nest in `replies` |
| Replies on demand | `lazyReplies = true` | honour `request.parent` |
| More than one page | `paginated = true` (the default) | use `request.page` or `request.cursor` |
| Scores | `scored = true` | fill `score` |
| Votes | `voting = true`, `downvotes = …` | implement `voteComment` |
| Posting | `posting = true` | implement `postComment` |
| Avatars | `avatars = true` | fill `avatarUrl` |

### Replies: nested or flat, not both

A site that returns replies inside their parent fills `replies`. A site that returns one flat list
fills `parentId` on each comment and `NovelCommentTree` nests it. Doing both for the same comment
doubles it, so pick whichever the site's own response shape makes cheaper.

### Dates

`postedAt` is epoch milliseconds. A site that only says "3 days ago" should leave it at zero rather
than computing a date from the phrase: the reader draws no timestamp at all for zero, which is
honest, where a computed one drifts every time the page is fetched.

### Errors

Throw. The message reaches the sheet, and an extension knows far better than the app what went
wrong with its own site. An empty `NovelCommentPage` is not an error — it means the chapter has no
comments, and the sheet says so differently.

## Third-party comment systems

Plenty of novel sites do not write their own comments; they embed someone else's. These are
protocols rather than sites, so the code for one is mostly the same wherever it turns up — but it
still belongs in each extension, because the shortname, the thread identifier and the post-id
lookup are the site's.

**WordPress** exposes `/wp-json/wp/v2/comments?post=<id>&per_page=<n>&page=<n>`. Each entry carries
`id`, `parent` (0 at the top level), `author_name`, `author_avatar_urls`, `content.rendered`,
`date_gmt` and `link` — which maps onto `NovelComment` almost field for field, with `parent`
becoming `parentId` when it is not zero. Reading needs no key. The work in the extension is finding
the post id for a chapter URL.

**Disqus** exposes `https://disqus.com/api/3.0/threads/listPosts.json`, which wants a forum
shortname, a thread identifier and an API key. The shortname is in the page's embed script; the key
is per-application. An extension that goes this way should treat the key as a
`ConfigurableSource` preference rather than shipping one.

Neither of these has been checked against any site this fork has an extension for. Treat the field
names above as a starting point to verify, not as something already known to work here. The sites
this fork does have extensions for use neither — both serve their own JSON.

## Reading a site's own API

Most novel sites with comments neither embed a third party nor render comments into the HTML: they
serve them from their own JSON endpoint and draw them in the browser. So `asJsoup()` on the chapter
page finds an empty container and nothing else.

Two things make that tractable without guessing:

- **The page usually ships its own ids.** A server-rendered app embeds its state in the document —
  `__NEXT_DATA__` for Next.js, an inline `window.__INITIAL_STATE__` or similar elsewhere. The
  chapter and novel identifiers the comment endpoint wants are normally in there, which means the
  extension needs no extra lookup request: it already fetches the chapter page.
- **The endpoint is in the site's own JavaScript.** Front ends keep a table of API paths, usually
  with `:placeholder` segments. Finding that table is faster and far more reliable than probing
  URLs, and it gives the exact parameter names.

### A 200 is not proof of filtering

The hazard worth designing against: an API that ignores query parameters it does not recognise and
answers with an unfiltered, site-wide list — with a perfectly cheerful `200`. A probe that looks
like it filtered can in fact be every comment on the site, and the mistake is invisible until a
reader opens a chapter and sees strangers discussing a different novel.

So:

1. **Prefer a path over a query parameter.** `…/comments/chapter/<id>` cannot silently degrade; a
   wrong path fails loudly. A filter expressed as `?chapter=<id>` can be dropped in silence.
2. **Check that the response belongs to what you asked for.** If items carry the entity they belong
   to, reject any that name something else. This is the check that still works after the site
   redesigns its API.
3. **Watch the response shape.** A scoped endpoint and a global feed usually differ structurally —
   one paginates by cursor, the other reports a grand total. A field that should not be there is a
   reliable signal you are on the wrong endpoint.
4. **Sanity-check against a count the page already told you.** If the chapter page says there are
   thirteen comments and the feed implies thousands, stop.

Throwing here is right. An extension that returns the wrong site's comments is worse than one that
returns none.

## Ratings and reactions

Implement `NovelCommentFeedbackSource` alongside `NovelCommentSource` to supply a review's
`NovelCommentRating`, separate `likes` and `dislikes` totals, and additional
`NovelCommentReaction` labels, emoji, counts and selected states. Return these from the
cached response in `getCommentFeedback`; it is called during rendering and must not fetch.

Ratings are separate from a comment's net score. Reaction totals are read-only metadata, with
additional site actions accessible through the comment's permalink. Only the existing `voting`
capability enables vote buttons, and `downvotes` controls whether a negative vote is offered.
Unknown counts stay null; never substitute zero for a count the site does not provide or derive
two counts from a net total. The UI uses the same thumbs for likes/dislikes and up/down votes.

This optional interface leaves the original API constructors and copy methods unchanged, so
installed extension APKs remain compatible. The app also recognizes the leading
`<b>★ 4.5 / 5.0</b><br>` review header emitted by older extensions and renders it as a rating.
Other comment text is retained as written.

Sources with distinct discussions and reviews can also implement `NovelCommentFeedSource`.
Each `NovelCommentFeed` declares its own label and capabilities, including scope and sort options.
The app presents tabs when more than one feed applies, and keeps their pagination, replies and
cached results separate. The original `getComments(request)` remains the single-feed fallback.

Replies start behind an `X replies` row while the parent body remains visible. Opening it shows
cached children immediately; a parent with only a count fetches its first reply page through the
existing lazy-reply API. Closing the row does not discard replies or let a late response reopen it.
Ratings sit beside the author, immediately before the comment menu, with fractional stars and the
exact numeric value. Both reaction counts remain visible, including zero.

## Shared across extensions

A novel's comments are not only its own source's. When the sheet opens, the app looks for the same
novel on every other installed, enabled comment source and reads their threads alongside, all
together by default or one extension at a time through a filter. Three things about an extension
decide how well that works:

- **Titles.** A match is a search result whose title equals the novel's once case and punctuation
  are ignored — nothing looser, because another novel's discussion is worse than none. Search
  results carrying the site's own full title match best.
- **Feed keys.** Tabs from different extensions are merged by `NovelCommentFeed.key`. Use
  `comments` for discussion and `reviews` for reviews; a source that declares no feeds counts as
  `comments`. So a site whose only listing is reviews should declare a single `reviews` feed, or
  its reviews land in the comments tab beside other sites' discussion.
- **Chapter numbers.** A chapter's comments on another site are found by number: the reader's
  chapter number, looked up in the other source's chapter list. Fill `chapter_number`, or give
  chapters names `ChapterRecognition` can read a number from.

Ids only need to be unique on their own site; the app keeps sites apart. Sorting a merged thread is
the app's own, since one site's "top" cannot rank another's, and posting is offered only while one
extension is showing.

## Testing one

The app's own `NovelCommentTreeTest` covers the nesting, so an extension does not have to. What is
worth checking by hand, once, on a real chapter:

1. A chapter with no comments shows the empty message, not a spinner and not an error.
2. A chapter with one page shows no "load more".
3. Opening each reply row reveals its next level; a reply three deep is indented three rails.
4. Whatever the site calls its default sort is selected in the sort menu.
5. Unknown counts remain absent, while real zeros remain visible beside the appropriate thumb.
