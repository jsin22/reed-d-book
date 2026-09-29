package dev.reedd.data.readium

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.StringSearchService
import org.readium.r2.shared.publication.services.search.searchServiceFactory
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * The Readium toolkit, built once and shared.
 *
 * Both the importer (metadata and cover) and the reader (rendering) need an open
 * [Publication], and constructing the parser chain is not free, so the pieces
 * live here rather than being rebuilt per screen.
 *
 * No PDF factory is supplied: this app only ever opens EPUBs, and pulling in a
 * PDF engine would add a native library for nothing.
 */
class ReadiumComponents(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient = DefaultHttpClient()

    val assetRetriever = AssetRetriever(
        contentResolver = appContext.contentResolver,
        httpClient = httpClient,
    )

    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = appContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
        // Every publication this app opens gets in-book search for free --
        // readium-shared already ships a full implementation
        // (StringSearchService), it just isn't attached by anything in the
        // default parser chain. Not customized further (ICU vs. the naive
        // algorithm, snippet length): the defaults are exactly what
        // Readium's own docs point to for "just want search to work".
        onCreatePublication = {
            servicesBuilder.searchServiceFactory = StringSearchService.createDefaultFactory()
        },
    )

    /**
     * Opens a local epub.
     *
     * Readium reports failure through `Try` rather than exceptions, so both
     * stages are unwrapped into one [ReadiumError] for callers.
     *
     * The caller owns the returned [Publication] and must [Publication.close] it.
     */
    suspend fun open(file: File): Result<Publication> {
        val asset: Asset = assetRetriever.retrieve(file)
            .getOrNull()
            ?: return Result.failure(ReadiumError("cannot read ${file.name}: unrecognised format"))

        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrNull()
            ?: run {
                asset.close()
                return Result.failure(ReadiumError("cannot open ${file.name} as a publication"))
            }
        return Result.success(publication)
    }
}

class ReadiumError(message: String) : Exception(message)
