package com.upcpocket.app.directory

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DirectoryCampus(val id: String, val label: String) {
    @SerialName("tdw")
    TANGDAOWAN("tdw", "唐岛湾"),

    @SerialName("gzk")
    GUZHENKOU("gzk", "古镇口"),
}

@Serializable
data class DirectoryMapTarget(
    val campus: DirectoryCampus,
    val placeId: String,
    val name: String,
)

@Serializable
internal data class DirectoryUnit(
    val id: String,
    val name: String,
    val college: Boolean,
    val introduction: String = "",
    val aliases: List<String> = emptyList(),
)

@Serializable
internal data class DirectoryOffice(
    val id: String,
    val unitId: String,
    val name: String,
    val category: String,
    val duties: String = "",
    val consultations: List<String> = emptyList(),
    val campus: DirectoryCampus? = null,
    val region: String = "",
    val address: String = "",
    val phones: List<DirectoryPhone> = emptyList(),
    val email: String = "",
    val mapTarget: DirectoryMapTarget? = null,
    val sources: List<String> = emptyList(),
    val checkedOn: String = "",
)

@Serializable
internal data class DirectoryPhone(
    val number: String,
    val label: String = "",
)

internal data class DirectorySearchResult(val unit: DirectoryUnit, val office: DirectoryOffice?)

@Serializable
private data class DirectoryCatalog(val units: List<DirectoryUnit>, val offices: List<DirectoryOffice>)

internal class DirectoryData private constructor(catalog: DirectoryCatalog) {
    val units = catalog.units
    val offices = catalog.offices

    private val officesByUnit = offices.groupBy(DirectoryOffice::unitId)
    private val searchIndex = units.flatMap { unit ->
        val names = listOf(unit.name) + unit.aliases
        val organization = SearchIndex(
            result = DirectorySearchResult(unit, null),
            name = unit.name,
            aliases = unit.aliases,
            details = listOf(unit.introduction),
        )
        listOf(organization) + offices(unit.id, null).map { office ->
            SearchIndex(
                result = DirectorySearchResult(unit, office),
                name = office.name,
                details = listOf(
                    office.category,
                    office.duties,
                    office.campus?.label.orEmpty(),
                    office.region,
                    office.address,
                    office.email,
                ) + office.consultations + office.phones.flatMap { listOf(it.number, it.label) },
                parentNames = names,
            )
        }
    }

    fun units(campus: DirectoryCampus?, colleges: Boolean): List<DirectoryUnit> =
        units.filter { it.college == colleges && includesCampus(it.id, campus) }

    fun offices(unitId: String, campus: DirectoryCampus?): List<DirectoryOffice> =
        officesByUnit[unitId].orEmpty().filter { campus == null || it.campus == campus }

    fun search(query: String, campus: DirectoryCampus?): List<DirectorySearchResult> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val terms = query.split(WHITESPACE).map(::normalize).filter(String::isNotEmpty)
        return searchIndex
            .asSequence()
            .filter { entry ->
                if (entry.result.office == null) {
                    includesCampus(entry.result.unit.id, campus)
                } else {
                    campus == null || entry.result.office.campus == campus
                }
            }
            .mapNotNull { entry -> entry.rank(normalized, terms)?.let { rank -> rank to entry.result } }
            .sortedBy { (rank, _) -> rank }
            .map { (_, result) -> result }
            .toList()
    }

    private fun includesCampus(unitId: String, campus: DirectoryCampus?): Boolean =
        campus == null || officesByUnit[unitId].orEmpty().any { it.campus == campus }

    private class SearchIndex(
        val result: DirectorySearchResult,
        name: String,
        details: List<String>,
        aliases: List<String> = emptyList(),
        parentNames: List<String> = emptyList(),
    ) {
        private val name = normalize(name)
        private val aliases = aliases.map(::normalize)
        private val fields = listOf(this.name) + this.aliases + details.map(::normalize)
        private val parentNames = parentNames.map(::normalize)
        private val combinedFields = fields + this.parentNames

        fun rank(query: String, terms: List<String>): Int? = when {
            query == name -> 0
            query in aliases -> 1
            query in name -> 2
            fields.any { query in it } -> 3
            terms.size > 1 && terms.all { term -> combinedFields.any { term in it } } -> 4
            parentNames.any { query in it } -> 5
            else -> null
        }
    }

    companion object {
        private const val ASSET = "campus_directory.json"
        private val WHITESPACE = Regex("\\s+")
        private val SEARCH_PUNCTUATION = Regex("[^\\p{L}\\p{N}]")

        internal fun parse(raw: String): DirectoryData = DirectoryData(Json.decodeFromString<DirectoryCatalog>(raw))

        suspend fun load(context: Context): DirectoryData = withContext(Dispatchers.IO) {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }

        private fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(SEARCH_PUNCTUATION, "")
    }
}
