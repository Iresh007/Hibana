package eu.kanade.tachiyomi.source.model

/**
 * Host runtime for the Tachiyomi/Mihon extensions-lib model types. Real extension
 * APKs are compiled against these as `compileOnly`, so Hibana must supply them at
 * runtime under these exact package + member names. Kept faithful to the
 * extensions-lib surface the current keiyoushi (coroutine) extensions use.
 */

enum class UpdateStrategy { ALWAYS_UPDATE, ONLY_FETCH_ONCE }

interface SManga {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: UpdateStrategy
    var initialized: Boolean

    fun copyFrom(other: SManga) {
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        status = other.status
        update_strategy = other.update_strategy
        if (!initialized) initialized = other.initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga = SMangaImpl()
    }
}

class SMangaImpl : SManga {
    override var url: String = ""
    override var title: String = ""
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = 0
    override var thumbnail_url: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false
}

interface SChapter {
    var url: String
    var name: String
    var date_upload: Long
    var chapter_number: Float
    var scanlator: String?

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
    }

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}

class SChapterImpl : SChapter {
    override var url: String = ""
    override var name: String = ""
    override var date_upload: Long = 0
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
}

open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
) {
    val number: Int get() = index + 1
}

data class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean)
