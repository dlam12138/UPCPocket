package com.upcpocket.app.todo

import androidx.room.withTransaction
import com.upcpocket.app.todo.data.PersonalScheduleEntity
import com.upcpocket.app.todo.data.TodoDatabase
import com.upcpocket.app.todo.data.toDomain
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 个人日程的 Room 实现。
 *
 * 数据库与 DAO 都在首次真正读写时才取得，插件装配不会打开数据库。所有写入在
 * 同一事务内完成；更新与删除以行内 revision 条件保证旧表单不会覆盖新内容。
 */
internal class PersonalScheduleService(
    databaseProvider: () -> TodoDatabase,
) : PersonalScheduleStore {

    private val database: TodoDatabase by lazy(databaseProvider)
    private val dao by lazy { database.personalScheduleDao() }

    override fun observeWindow(
        fromInclusive: Long,
        untilExclusive: Long,
    ): Flow<List<PersonalSchedule>> =
        dao.observeWindow(fromInclusive, untilExclusive)
            .map { entities -> entities.map(PersonalScheduleEntity::toDomain) }
            .distinctUntilChanged()

    override suspend fun byId(id: String): PersonalSchedule? =
        dao.byId(id)?.toDomain()

    override suspend fun add(
        title: String,
        startAt: Long,
        endAt: Long,
        location: String?,
        note: String?,
    ): PersonalSchedule {
        val normalized = normalize(title, startAt, endAt, location, note)
        val entity = PersonalScheduleEntity(
            id = UUID.randomUUID().toString(),
            title = normalized.title,
            startAt = startAt,
            endAt = endAt,
            location = normalized.location,
            note = normalized.note,
            revision = 1,
        )
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun update(
        id: String,
        expectedRevision: Int,
        title: String,
        startAt: Long,
        endAt: Long,
        location: String?,
        note: String?,
    ): PersonalSchedule? {
        val normalized = normalize(title, startAt, endAt, location, note)
        return database.withTransaction {
            val updated = dao.updateIfRevision(
                id = id,
                expectedRevision = expectedRevision,
                title = normalized.title,
                startAt = startAt,
                endAt = endAt,
                location = normalized.location,
                note = normalized.note,
            )
            if (updated == 0) return@withTransaction null
            dao.byId(id)?.toDomain()
        }
    }

    override suspend fun delete(id: String, expectedRevision: Int): PersonalSchedule? =
        database.withTransaction {
            // 先按最新行复核 revision，成功删除时把删除前快照交给调用方。
            val current = dao.byId(id) ?: return@withTransaction null
            if (current.revision != expectedRevision) return@withTransaction null
            if (dao.deleteIfRevision(id, expectedRevision) == 0) return@withTransaction null
            current.toDomain()
        }

    private fun normalize(
        title: String,
        startAt: Long,
        endAt: Long,
        location: String?,
        note: String?,
    ): NormalizedSchedule {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "日程标题不能为空" }
        require(endAt > startAt) { "日程结束时间必须晚于开始时间" }
        return NormalizedSchedule(
            title = normalizedTitle,
            location = location?.trim()?.takeIf(String::isNotEmpty),
            note = note?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private data class NormalizedSchedule(
        val title: String,
        val location: String?,
        val note: String?,
    )
}
