package dev.reedd.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
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

    /**
     * Upload an .epub. Returns as soon as the file is on disk server-side, with
     * `status: "queued"` and the id to poll.
     *
     * `voice` and `speed` are plain text parts, not JSON: the server reads them
     * with FastAPI's `Form(...)`.
     */
    @Multipart
    @POST("api/jobs")
    suspend fun createJob(
        @Part file: MultipartBody.Part,
        @Part("voice") voice: RequestBody?,
        @Part("speed") speed: RequestBody?,
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
}
