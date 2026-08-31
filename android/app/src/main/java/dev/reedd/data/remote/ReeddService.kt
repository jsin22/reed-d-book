package dev.reedd.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The conversion server's HTTP surface, as documented in `server/README.md`.
 *
 * The two file downloads are deliberately absent: they need `Range` headers,
 * streaming to disk and byte-level progress, which is [dev.reedd.data.download]'s
 * job with a raw OkHttp call rather than Retrofit's.
 */
interface ReeddService {

    /** Liveness. Never requires the token, so it doubles as "is this the right host?". */
    @GET("api/health")
    suspend fun health(): HealthDto

    @GET("api/voices")
    suspend fun voices(): VoicesDto

    /** Every engine and its voices, for a two-level (engine, then voice) picker. */
    @GET("api/engines")
    suspend fun engines(): EnginesDto

    /**
     * Upload an .epub. Returns as soon as the file is on disk server-side, with
     * `status: "queued"` and the id to poll.
     *
     * `voice`, `speed`, `engine`, `title` and `author` are plain text parts, not
     * JSON: the server reads them with FastAPI's `Form(...)`. `title`/`author`
     * are optional and only used server-side to kick off the background
     * category/genre lookup (see [JobDto.category]/[JobDto.genres]).
     */
    @Multipart
    @POST("api/jobs")
    suspend fun createJob(
        @Part file: MultipartBody.Part,
        @Part("voice") voice: RequestBody?,
        @Part("speed") speed: RequestBody?,
        @Part("engine") engine: RequestBody?,
        @Part("title") title: RequestBody?,
        @Part("author") author: RequestBody?,
    ): JobDto

    @GET("api/jobs")
    suspend fun listJobs(@Query("limit") limit: Int = 50): JobListDto

    @GET("api/jobs/{jobId}")
    suspend fun job(@Path("jobId") jobId: String): JobDto

    /** Cancels first if the job is still running, then reclaims its disk. */
    @DELETE("api/jobs/{jobId}")
    suspend fun deleteJob(@Path("jobId") jobId: String): DeleteDto

    /** audiblez' output for this job, ffmpeg included. `text/plain`, so not a DTO. */
    @GET("api/jobs/{jobId}/log")
    suspend fun log(@Path("jobId") jobId: String): ResponseBody

    /**
     * Post a crash report from the previous run as plain text.
     *
     * The server writes it to `data/crashes/` and logs it, which is the most
     * convenient way to read an Android stack trace when the emulator does not run
     * and the phone is not tethered.
     */
    @POST("api/diagnostics/crash")
    suspend fun reportCrash(@Body report: RequestBody): ResponseBody

    /** Who the current token belongs to -- used to show "logged in as" and to
     *  decide whether to offer the Admin screen. */
    @GET("api/me")
    suspend fun me(): MeDto

    // -- admin only; see server/README.md "Sharing with others" --

    /** Every job on the server, unfiltered, with the owner's email joined in. */
    @GET("api/admin/jobs")
    suspend fun adminJobs(@Query("limit") limit: Int = 50): AdminJobListDto

    @POST("api/admin/jobs/{jobId}/public")
    suspend fun setJobPublic(@Path("jobId") jobId: String, @Body body: PublicUpdateDto): JobDto

    @GET("api/admin/users")
    suspend fun adminUsers(): UserListDto

    /** Creates the account and, if the server has SMTP configured, emails them
     *  the token; either way the token comes back here too, in case it needs
     *  to be hand-delivered instead. */
    @POST("api/admin/users")
    suspend fun inviteUser(@Body body: InviteRequestDto): InviteResultDto

    /** Revokes a user's access; the server refuses to delete the caller's own account. */
    @DELETE("api/admin/users/{userId}")
    suspend fun deleteUser(@Path("userId") userId: String): UserDeleteDto

    /** Whether the category/genre lookup is currently working -- see [MetadataHealthDto]. */
    @GET("api/admin/metadata-health")
    suspend fun metadataHealth(): MetadataHealthDto
}
