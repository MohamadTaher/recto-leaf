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
that, with no vote arrows and no reply box. Nothing has to be stubbed.

## Growing it

Everything else is a field on the capabilities plus the code to back it. Claim a capability only
once the code behind it works; the sheet reads the capability, not the method.

| Want | Set | Then |
|---|---|---|
| A default order | `sorts = listOf(NovelCommentSort("new", "Newest"), …)` | read `request.sort.key` |
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

### Pictures and GIFs

Send them as `<img src>`, or `<video src>` for a GIF a picker hands out as a short `.mp4` or
`.webm`. The sheet draws them beneath the comment's words, GIFs and videos moving, and a tap opens
the file. It cannot put one inside a line of text, so an emoji that is a picture reads better as its
`:name:`. A site whose plain-text field flattens pictures away usually has a richer one beside it;
use that, or a comment that is only a GIF arrives empty.

### Dates

`postedAt` is epoch milliseconds. A site that only says "3 days ago" should leave it at zero rather
than computing a date from the phrase: the reader draws no timestamp at all for zero, which is
honest, where a computed one drifts every time the page is fetched.

### Errors

Throw. The message reaches the sheet, and an extension knows far better than the app what went
wrong with its own site. An empty `NovelCommentPage` is not an error — it means the chapter has no
comments, and the sheet says so differently.

Do not, however, rely on the app waiting for ever. Every call into a source — a listing, a search,
a chapter list, a vote, a post — is given thirty seconds, after which the thread reports a timeout
against that source's name and stops. A site that needs longer than that needs its own retry inside
the extension, where it can be done once and cheaply, rather than a request left hanging.

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
names above as a starting point to verify, not as something already known to work here.

**wpDiscuz** is a WordPress plugin that often has the REST route above closed. It answers its own
AJAX actions on `admin-ajax.php` instead: `wpdLoadMoreComments` for a page of top-level comments,
with the page's `postId` (in the page's `wc_post_id` setting), `sorting`, a zero-based `offset` and
the previous page's `last_parent_id` as `lastParentId`; and `wpdShowReplies` with a `commentId` for
one thread's replies, nested as child elements. Sites customise it — one keys each chapter's thread
by a `chapterId` of its own — so capture what the site's "load more" button actually sends.

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
cached response in `getCommentFeedback`; it must not fetch. The app calls it once per comment, as
that comment's page lands, and keeps the answer for as long as the thread lives — so it has to be a
lookup into what the source has already parsed, and it has to give the same answer twice.

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
The app fetches every feed that applies and keeps their pagination, replies and cached results
separate; the sheet shows them together, with a filter for reviews or comments alone. The original
`getComments(request)` remains the single-feed fallback.

Replies start behind an `X replies` row while the parent body remains visible. Opening it shows
cached children immediately; a parent with only a count fetches its first reply page through the
existing lazy-reply API. Closing the row does not discard replies or let a late response reopen it.
Ratings sit beside the author, immediately before the comment menu, with fractional stars and the
exact numeric value. Both reaction counts remain visible, including zero.

## Shared across extensions

A novel's comments are not only its own source's. When the sheet opens, the app looks for the same
novel on every other installed, enabled comment source and reads their threads alongside, all
together by default or one extension at a time through a filter. Every row looks the same whichever
extension sent it: the name with the date beneath, and the rating — or, without one, the extension's
name — on the right. A site's own labels for a user (tiers, titles, taglines) are not shown. Three
things about an extension decide how well that works:

- **Titles.** A match is a search result whose title equals the novel's once case and punctuation
  are ignored — nothing looser, because another novel's discussion is worse than none. Search
  results carrying the site's own full title match best.
- **Feed keys.** The sheet's review filter reads `NovelCommentFeed.key`, and compares it against
  `NovelCommentFeed.REVIEWS` and nothing else — not the label, not the scope. Use the constant
  rather than spelling the string: a feed keyed `review`, `ratings` or `user-reviews` is filed as
  comments with nothing to say it was. Every other feed — including the one a source without feeds
  is taken to have, `NovelCommentFeed.COMMENTS` — is comments. A site whose only listing is reviews
  should declare a single `REVIEWS` feed.
- **Chapter numbers.** A chapter's comments on another site are found by number: the reader's
  chapter number, looked up in the other source's chapter list. Fill `chapter_number`, or give
  chapters names `ChapterRecognition` can read a number from. A site whose chapter list takes many
  requests to fetch should implement `NovelCommentChapterSource` and find the one chapter itself.

Ids only need to be unique within one feed of one site; the app keeps feeds and sites apart. The
one exception is `NovelCommentFeedbackSource`, which is handed a comment and nothing else and so
has only the id to key by: a source whose feeds can both produce an id `1` must make them distinct
itself, the way a `review:` prefix does. The app reads a page's feedback under the same lock as the
fetch that produced it, so a source's feeds are asked one at a time and never overwrite each
other's answers — but only the extension can keep its own map straight. The
order is always the app's own, since one site's "top" cannot rank another's: each feed is asked for
the first of its `sorts`, so put the site's default first, and the sheet ranks what arrives
by likes — the `likes` a `NovelCommentFeedbackSource` gives, or else the comment's `score`.
Posting is offered only while one feed of one extension is showing.

The novel screen can search these other sources even when the novel's own source has no comments.
If none has a matching novel, the sheet shows an empty discussion after the search finishes.

## Checking one

Most of what goes wrong with a new extension is the shape, not the site, and the shape can be
checked without a network. `NovelCommentConformance` lives in `:novel-api` and returns what it
found rather than asserting, so it drops into an extension's own unit tests:

```kotlin
@Test
fun `says nothing about itself that is not true`() {
    NovelCommentConformance.check(SomeSite()) shouldBe emptyList()
}

@Test
fun `serves a page the app can draw`() = runBlocking {
    val page = SomeSite().getComments(request)
    NovelCommentConformance.check(page, SomeSite().commentCapabilities) shouldBe emptyList()
}
```

It holds four things against each other: a source's capabilities against themselves (`downvotes`
without `voting`, `lazyReplies` on a flat list, a sort key offered twice), the feeds against the
app's one magic string (a feed that looks like reviews but is not keyed `NovelCommentFeed.REVIEWS`,
two feeds sharing a key), a real page against the feed that served it (a score on an unscored feed,
a reply past the declared `maxDepth`, a repeated id, an id carrying a control character, a cursor
on a last page), and `getCommentFeedback` against its own contract (asked twice, it must answer
the same).

Debug builds of the app run the page and feedback checks on every page that lands and write what
they find to logcat under the source and feed name, so an extension being written reports its own
mistakes without any wiring.

What is still worth checking by hand, once, on a real chapter:

1. A chapter with no comments shows the empty message, not a spinner and not an error.
2. A chapter with one page shows no "load more".
3. Opening each reply row reveals its next level; a reply three deep is indented three rails.
4. The first of the declared `sorts` is the order the site itself defaults to.
5. Unknown counts remain absent, while real zeros remain visible beside the appropriate thumb.
6. The novel is actually found on the other installed sources — see **Titles** above. Nothing says
   so when it is not; the extension filter simply never lists that site.
