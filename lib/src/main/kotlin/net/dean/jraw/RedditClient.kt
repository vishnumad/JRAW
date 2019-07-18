package net.dean.jraw

import com.squareup.moshi.Types
import net.dean.jraw.databind.Enveloped
import net.dean.jraw.http.*
import net.dean.jraw.models.*
import net.dean.jraw.models.internal.RedditExceptionStub
import net.dean.jraw.models.internal.SubredditName
import net.dean.jraw.models.internal.SubredditSearchResultContainer
import net.dean.jraw.oauth.*
import net.dean.jraw.pagination.BarebonesPaginator
import net.dean.jraw.pagination.DefaultPaginator
import net.dean.jraw.pagination.SearchPaginator
import net.dean.jraw.pagination.SubredditSearchPaginator
import net.dean.jraw.ratelimit.LeakyBucketRateLimiter
import net.dean.jraw.ratelimit.RateLimiter
import net.dean.jraw.references.*
import okhttp3.HttpUrl
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Specialized class for sending requests to [oauth.reddit.com](https://www.reddit.com/dev/api/oauth).
 *
 * RedditClients cannot be instantiated directly through the public API. See the
 * [OAuthHelper][net.dean.jraw.oauth.OAuthHelper] class.
 *
 * This class can also be used to send HTTP requests to other domains, but it is recommended to just use an
 * [NetworkAdapter] to do that since all requests made using this class are rate limited using [rateLimiter].
 *
 * By default, all network activity that originates from this class are logged with [logger]. You can provide your own
 * [HttpLogger] implementation or turn it off entirely by setting [logHttp] to `false`.
 *
 * Any requests sent by RedditClients are rate-limited on a per-instance basis. That means that it is possible to
 * consume your app's quota of 60 requests per second using more than one RedditClient authenticated under the same
 * OAuth2 app credentials operating independently of each other. For that reason, it is recommended to have only one
 * instance of the RedditClient per application.
 *
 * By default, any request that responds with a server error code (5XX) will be retried up to five times. You can change
 * this by changing [retryLimit].
 */
class RedditClient internal constructor(
    /** How this client will send HTTP requests */
    val http: NetworkAdapter,
    initialOAuthData: OAuthData,
    creds: Credentials,
    /** The TokenStore to assign to [AuthManager.tokenStore] */
    tokenStore: TokenStore = NoopTokenStore(),
    /** A non-null value will prevent a request to /api/v1/me to figure out the authenticated username */
    overrideUsername: String? = null
) {
    /** Every HTTP request/response will be logged with this, unless [logHttp] is false */
    var logger: HttpLogger = SimpleHttpLogger()

    /** Whether or not to log HTTP requests */
    var logHttp = true

    /**
     * How many times this client will retry requests that result in 5XX erorr codes. Defaults to 5. Set to a number
     * less than 1 to disable this functionality.
     */
    var retryLimit: Int = DEFAULT_RETRY_LIMIT

    /**
     * How this client will determine when it can send a request.
     *
     * By default, this RateLimiter is a [LeakyBucketRateLimiter] with a capacity of 5 permits and refills them at a
     * rate of 1 token per second.
     */
    var rateLimiter: RateLimiter = LeakyBucketRateLimiter(BURST_LIMIT, RATE_LIMIT, TimeUnit.SECONDS)

    /** If true, any time a request is made, the access token will be renewed if necessary. */
    var autoRenew = true

    internal var forceRenew = false

    /** How this client manages (re)authentication */
    var authManager = AuthManager(http, creds)

    /** The type of OAuth2 app used to authenticate this client */
    val authMethod: AuthMethod = creds.authMethod

    /**
     * If true, this client shouldn't be used any more. Any attempts to send requests while this is true will throw an
     * IllegalStateException.
     */
    internal var loggedOut: Boolean = false

    init {
        // Use overrideUsername if available, otherwise try to fetch the name from the API. We can't use
        // me().about().name since that would require a valid access token (we have to call authManager.update after
        // we have a valid username so TokenStores get the proper name once it gets updated). Instead we directly
        // make the request and parse the response.
        authManager.currentUsername = overrideUsername ?: try {
                val me = request(HttpRequest.Builder()
                    .url("https://oauth.reddit.com/api/v1/me")
                    .header("Authorization", "bearer ${initialOAuthData.accessToken}")
                    .build()).deserialize<Map<*, *>>()

                // Avoid deserializing an Account because it's harder to mock an response to Account while testing
                me["name"] as? String ?:
                    throw IllegalArgumentException("Expected a name")
            } catch (e: ApiException) {
                // Delay throwing an exception until `requireAuthenticatedUser()` is called
                null
            }

        authManager.tokenStore = tokenStore
        authManager.update(initialOAuthData)
    }

    /**
     * Creates a [HttpRequest.Builder], setting `secure(true)`, `host("oauth.reddit.com")`, and the Authorization header
     */
    fun requestStub(): HttpRequest.Builder {
        return HttpRequest.Builder()
            .secure(true)
            .host("oauth.reddit.com")
            .header("Authorization", "bearer ${authManager.accessToken}")
    }

    @Throws(NetworkException::class)
    private fun request(r: HttpRequest, retryCount: Int = 0): HttpResponse {
        if (loggedOut)
            throw IllegalStateException("This client is logged out and should not be used anymore")

        var req = r

        // Hacky fix to token refresh issue
        if (!authManager.canRenew()) {
            // Cannot renew, so refresh token in AuthManager is null for some reason
            // We can fetch the stored data in the token store and update the AuthManager
            val username = authManager.currentUsername()
            val authData = authManager.tokenStore.fetchLatest(username)
            val refreshToken = authManager.tokenStore.fetchRefreshToken(username)

            if (authData != null && refreshToken != null) {
                val newAuthData = OAuthData.create(
                    authData.accessToken,
                    authData.scopes,
                    refreshToken,
                    authData.expiration
                )
                authManager.update(newAuthData)
            }
        }

        if (forceRenew || (autoRenew && authManager.needsRenewing() && authManager.canRenew())) {
            authManager.renew()

            // forceRenew is a one-time thing, usually used when given an OAuthData that only contains valid refresh
            // token and a made-up access token, expiration, etc.
            forceRenew = false

            // The request was intended to be sent with the previous access token. Since we've renewed it, we have to
            // modify that header.
            req = req.newBuilder().header("Authorization", "bearer ${authManager.accessToken}").build()
        }

        // Add the raw_json=1 query parameter if requested
        if (req.rawJson) {
            val url = HttpUrl.parse(req.url)!!
            // Make sure we specify a value for raw_json exactly once

            var newUrl = url.newBuilder()

            if (url.queryParameterValues("raw_json") != listOf("1")) {
                // Make sure to remove all previously specified values of raw_json if more than one existed or given a
                // different value.
                newUrl = newUrl.removeAllQueryParameters("raw_json")
                    .addQueryParameter("raw_json", "1")
            }

            req = req.newBuilder().url(newUrl.build()).build()
        }

        // Only ratelimit on the first try
        if (retryCount == 0)
            rateLimiter.acquire()

        val res = if (logHttp) {
            // Log the request and response, returning 'res'
            val tag = logger.request(req)
            val res = http.execute(req)
            logger.response(tag, res)
            res
        } else {
            http.execute(req)
        }

        if (res.code in 500..599 && retryCount < retryLimit) {
            return request(req, retryCount + 1)
        }

        val type = res.raw.body()?.contentType()

        // Try to find any API errors embedded in the JSON document
        if (type != null && type.type() == "application" && type.subtype() == "json") {
            // Make the adapter lenient so we're not required to read the entire body
            val adapter = JrawUtils.adapter<RedditExceptionStub<*>>().lenient()
            val stub = if (res.body == "") null else adapter.fromJson(res.body)

            // Reddit has some legacy endpoints that return 200 OK even though the JSON contains errors
            if (stub != null) {
                val ex = stub.create(NetworkException(res))
                if (ex != null) throw ex
            }
        }

        // Make sure we're still failing on non-success status codes if we couldn't find an API error in the JSON
        if (!res.successful)
            throw NetworkException(res)

        return res
    }

    /**
     * Attempts to open a WebSocket connection at the given URL.
     */
    fun websocket(url: String, listener: WebSocketListener): WebSocket {
        return http.connect(url, listener)
    }

    /**
     * Uses the [NetworkAdapter] to execute a synchronous HTTP request
     *
     * ```
     * val response = reddit.request(reddit.requestStub()
     *     .path("/api/v1/me")
     *     .build())
     * ```
     *
     * @throws NetworkException If the response's code is out of the range of 200..299.
     * @throws RedditException if an API error if the response comes back unsuccessful and a typical error structure is
     * detected in the response.
     */
    @Throws(NetworkException::class, RedditException::class)
    fun request(r: HttpRequest): HttpResponse = request(r, retryCount = 0)

    /**
     * Adds a little syntactic sugar to the vanilla `request` method.
     *
     * The [configure] function will be passed the return value of [requestStub], and that [HttpRequest.Builder] will be
     * built and send to `request()`. While this may seem a little complex, it's probably easier to understand through
     * an example:
     *
     * ```
     * val json = reddit.request {
     *     it.path("/api/v1/foo")
     *         .header("X-Foo", "Bar")
     *         .post(mapOf(
     *             "baz" to "qux"
     *         ))
     * }
     * ```
     *
     * This will execute `POST https://oauth.reddit.com/api/v1/foo` with the headers 'X-Foo: Bar' and
     * 'Authorization: bearer $accessToken' and a form body of `baz=qux`.
     *
     * For reference, this same request can be executed like this:
     *
     * ```
     * val json = reddit.request(reddit.requestStub()
     *     .path("/api/v1/me")
     *     .header("X-Foo", "Bar")
     *     .post(mapOf(
     *         "baz" to "qux"
     *     )).build())
     * ```
     *
     * @see requestStub
     */
    @Throws(NetworkException::class, RedditException::class)
    fun request(configure: (stub: HttpRequest.Builder) -> HttpRequest.Builder) = request(configure(requestStub()).build())

    /**
     * Creates a UserReference for the currently logged in user.
     *
     * @throws IllegalStateException If there is no authenticated user
     */
    @Throws(IllegalStateException::class)
    fun me() = SelfUserReference(this)

    /**
     * Creates a UserReference for any user
     *
     * @see me
     */
    fun user(name: String) = OtherUserReference(this, name)

    /**
     * Returns a Paginator builder that will iterate user subreddits. See [here](https://www.reddit.com/comments/6bqemt)
     * for more info.
     *
     * Possible `where` values:
     *
     *  - `new`
     *  - `popular`
     */
    @EndpointImplementation(Endpoint.GET_USERS_WHERE, type = MethodType.NON_BLOCKING_CALL)
    fun userSubreddits(where: String) = BarebonesPaginator.Builder.create<Subreddit>(this, "/users/${JrawUtils.urlEncode(where)}")

    /** Creates a [DefaultPaginator.Builder] to iterate posts on the front page */
    fun frontPage() = DefaultPaginator.Builder.create<Submission, SubredditSort>(this, baseUrl = "", sortingAlsoInPath = true)

    /** Creates a SearchPaginator.Builder to search for submissions in every subreddit */
    fun search(): SearchPaginator.Builder = SearchPaginator.everywhere(this)

    /**
     * Allows searching reddit for various communities. Recommended sorting here is "relevance," which is also the
     * default.
     *
     * Note that this is different from [search], which searches for submissions compared to this method, which searches
     * for subreddits. This is also different from [searchSubredditsByName] since this returns a Paginator and the other
     * returns a simple list.
     */
    @EndpointImplementation(Endpoint.GET_SUBREDDITS_SEARCH)
    fun searchSubreddits(): SubredditSearchPaginator.Builder = SubredditSearchPaginator.Builder(this)

    /**
     * Convenience function equivalent to
     *
     * ```java
     * searchSubredditsByName(new SubredditSearchQuery(queryString))
     * ```
     */
    fun searchSubredditsByName(query: String) = searchSubredditsByName(SubredditSearchQuery(query))

    /**
     * Searches for subreddits by name alone. A more general search for subreddits can be done using [searchSubreddits].
     */
    @EndpointImplementation(Endpoint.POST_SEARCH_SUBREDDITS)
    fun searchSubredditsByName(query: SubredditSearchQuery): List<SubredditSearchResult> {
        val container = request {
            it.endpoint(Endpoint.POST_SEARCH_SUBREDDITS)
                .post(mapOf(
                    "query" to query.query,
                    "exact" to query.exact?.toString(),
                    "include_over_18" to query.includeNsfw?.toString(),
                    "include_unadvertisable" to query.includeUnadvertisable?.toString()
                ).filterValuesNotNull())
        }.deserialize<SubredditSearchResultContainer>()

        return container.subreddits
    }

    /**
     * Creates a [SubredditReference]
     *
     * Reddit has some special subreddits:
     *
     * - /r/all - posts from every subreddit
     * - /r/popular - includes posts from subreddits that have opted out of /r/all. Guaranteed to not have NSFW content.
     * - /r/mod - submissions from subreddits the logged-in user moderates
     * - /r/friends - submissions from the user's friends
     *
     * Trying to use [SubredditReference.about], [SubredditReference.submit], or the like for these subreddits will
     * likely result in an API-side error.
     */
    fun subreddit(name: String) = SubredditReference(this, name)

    /**
     * Creates a [SubredditReference] to a "compound subreddit." This reference will be limited in some ways (for
     * example, you can't submit a post to a compound subreddit).
     *
     * This works by using a useful little-known trick. You can view multiple subreddits at one time by joining them
     * together with a `+`, like "/r/redditdev+programming+kotlin"
     *
     * Here's how to fetch 50 posts from /r/pics and /r/funny as a quick example:
     *
     * ```kotlin
     * val paginator = redditClient.subreddits("pics", "funny").posts().limit(50).build()
     * val posts = paginator.next()
     * ```
     */
    fun subreddits(first: String, second: String, vararg others: String) =
        SubredditReference(this, arrayOf(first, second, *others).joinToString("+"))

    /**
     * Tests if the client has the ability to access the given API endpoint. This method always returns true for script
     * apps.
     */
    fun canAccess(e: Endpoint): Boolean {
        val scopes = authManager.current?.scopes ?: return false
        // A '*' means all scopes, only used for scripts
        if (scopes.contains("*")) return true
        return scopes.contains(e.scope)
    }

    /**
     * Creates a SubredditReference for a random subreddit. Although this method is decorated with
     * [EndpointImplementation], it does not execute an HTTP request and is not a blocking call. This method is
     * equivalent to
     *
     * ```kotlin
     * reddit.subreddit("random")
     * ```
     *
     * @see SubredditReference.randomSubmission
     */
    @EndpointImplementation(Endpoint.GET_RANDOM, type = MethodType.NON_BLOCKING_CALL)
    fun randomSubreddit() = subreddit("random")

    /**
     * Creates either [SubmissionReference] or [CommentReference] depending on provided fullname.
     * Fullname format is defined as ${kind}_${id} (e.g. t3_6afe8u or t1_2bad4u).
     *
     * @see KindConstants
     * @throws IllegalArgumentException if the provided fullname doesn't match that format or the prefix is not
     * one of [KindConstants.COMMENT] or [KindConstants.SUBREDDIT]
     */
    fun publicContribution(fullname: String): PublicContributionReference {
        val parts = fullname.split('_')
        if (parts.size != 2)
            throw IllegalArgumentException("Fullname doesn't match the pattern KIND_ID")
        return when (parts[0]) {
            KindConstants.COMMENT -> comment(parts[1])
            KindConstants.SUBMISSION -> submission(parts[1])
            else -> throw IllegalArgumentException("Provided kind '${parts[0]}' is not a public contribution")
        }
    }

    /**
     * Creates a SubmissionReference. Note that `id` is NOT a full name (like `t3_6afe8u`), but rather an ID
     * (like `6afe8u`)
     */
    fun submission(id: String) = SubmissionReference(this, id)

    /**
     * Creates a CommentReference. Note that `id` is NOT a full name (like `t1_6afe8u`), but rather an ID
     * (like `6afe8u`)
     */
    fun comment(id: String) = CommentReference(this, id)

    /**
     * Creates a BarebonesPaginator.Builder that will iterate over the latest comments from the given subreddits when
     * built. If no subreddits are given, comments will be from any subreddit.
     */
    fun latestComments(vararg subreddits: String): BarebonesPaginator.Builder<Comment> {
        val prefix = if (subreddits.isEmpty()) "" else "/r/" + subreddits.joinToString("+")
        return BarebonesPaginator.Builder.create(this, "$prefix/comments")
    }

    /**
     * Creates a [BarebonesPaginator.Builder] that will iterate over gilded public contributions (i.e. posts and
     * comments) in the given subreddits when built.
     *
     * If no subreddits are given, contributions will be from logged-in user's frontpage subreddits.
     *
     * @see PublicContribution
     */
    fun gildedContributions(vararg subreddits: String): BarebonesPaginator.Builder<PublicContribution<*>> {
        val prefix = if (subreddits.isEmpty()) "" else "/r/" + subreddits.joinToString("+")
        return BarebonesPaginator.Builder.create(this, "$prefix/gilded")
    }

    /**
     * Returns the name of the logged-in user
     *
     * @throws IllegalStateException If there is no logged-in user
     */
    fun requireAuthenticatedUser(): String {
        if (authMethod.isUserless)
            throw IllegalStateException("Expected the RedditClient to have an active user, was authenticated with " +
                authMethod)
        return authManager.currentUsername ?: throw IllegalStateException("Expected an authenticated user")
    }

    /**
     * varargs version of `lookup` provided for convenience.
     */
    fun lookup(vararg fullNames: String) = lookup(listOf(*fullNames))

    /**
     * Attempts to find information for the given full names. Only the full names of submissions, comments, and
     * subreddits are accepted.
     */
    @EndpointImplementation(Endpoint.GET_INFO)
    fun lookup(fullNames: List<String>): Listing<Any> {
        if (fullNames.isEmpty()) return Listing.empty()

        val type = Types.newParameterizedType(Listing::class.java, Any::class.java)
        val adapter = JrawUtils.moshi.adapter<Listing<Any>>(type, Enveloped::class.java)
        return request {
            it.endpoint(Endpoint.GET_INFO, null)
                .query(mapOf("id" to fullNames.joinToString(",")))
        }.deserializeWith(adapter)
    }

    /**
     * Creates a reference to a live thread with the given ID.
     *
     * To create a live thread, use [SelfUserReference.createLiveThread]:
     *
     * ```kt
     * val ref: LiveThreadReference = redditClient.me().createLiveThread(LiveThread.Builder()
     *     ...
     *     .build())
     * ```
     */
    fun liveThread(id: String) = LiveThreadReference(this, id)

    @JvmOverloads
    @EndpointImplementation(Endpoint.GET_RECOMMEND_SR_SRNAMES)
    fun recommendedSubreddits(subs: List<String>, includeNsfw: Boolean? = null): List<String> {
        if (subs.isEmpty())
            return listOf()

        val query = mutableMapOf<String, String>()
        if (includeNsfw != null)
            query["over_18"] = includeNsfw.toString()

        val names: List<SubredditName> = request {
            it.path("/api/recommend/sr/${subs.joinToString(",") { JrawUtils.urlEncode(it) }}")
                .query(query)
        }.deserializeWith(JrawUtils.moshi.adapter(Types.newParameterizedType(List::class.java, SubredditName::class.java)))

        return names.map { it.name }
    }

    /**
     * Returns the live thread currently being featured by reddit, or null if there is none. On the website, this
     * appears above all posts on the front page.
     */
    @EndpointImplementation(Endpoint.GET_LIVE_HAPPENING_NOW)
    fun happeningNow(): LiveThread? {
        val res = request {
            it.endpoint(Endpoint.GET_LIVE_HAPPENING_NOW)
        }

        // A 204 response means there's nothing happening right now
        if (res.code == 204) return null

        return res.deserialize()
    }

    /** @inheritDoc */
    override fun toString(): String {
        return "RedditClient(username=${authManager.currentUsername()})"
    }

    /** Defines some static properties */
    companion object {
        /** Amount of requests per second reddit allows for OAuth2 apps (equal to 1) */
        const val RATE_LIMIT = 1L
        private const val BURST_LIMIT = 5L
        private const val DEFAULT_RETRY_LIMIT = 5
    }
}
