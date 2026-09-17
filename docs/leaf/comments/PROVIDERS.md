# Teaching a site its comments

All site knowledge lives in the extension. The app asks `NovelCommentSource` and draws what comes
back; it has no list of sites, no endpoint templates and no per-site parsing. So adding comments to
a site is one interface on one extension class, in `novel-extensions/`.

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

Neither of these has been checked against any site this fork has an extension for — see the
question about it in `QUESTIONS.md`. Treat the field names above as a starting point to verify, not
as something already known to work here.

## Testing one

The app's own `NovelCommentTreeTest` covers the nesting, so an extension does not have to. What is
worth checking by hand, once, on a real chapter:

1. A chapter with no comments shows the empty message, not a spinner and not an error.
2. A chapter with one page shows no "load more".
3. A reply three deep is indented three rails.
4. Whatever the site calls its default sort is the chip that starts selected.
5. Turning the source's `scored` off makes the numbers disappear rather than showing zeros.
