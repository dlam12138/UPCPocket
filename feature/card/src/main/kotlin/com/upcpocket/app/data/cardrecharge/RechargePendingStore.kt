package com.upcpocket.app.data.cardrecharge

import android.util.AtomicFile
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.platform.crypto.KeystoreAesGcmCrypto
import java.io.File
import kotlinx.serialization.json.*

internal data class RechargePending(
    val owner: SchoolStudentIdentity, val attemptId: String, val yuan: String,
    val orderId: String? = null, val payId: String? = null,
) {
    override fun toString() = "RechargePending([REDACTED])"
}

internal interface RechargePendingStore {
    fun read(owner: SchoolStudentIdentity): RechargePending?
    fun save(record: RechargePending)
    fun remove(record: RechargePending)
    fun forget(owner: SchoolStudentIdentity)
    fun clearAll()
}

/** The durable marker is written before posting; it contains no token or signing material. */
internal class EncryptedRechargePendingStore(
    private val directory: File,
    private val crypto: ByteCrypto = KeystoreAesGcmCrypto("upcpocket.card-recharge.v1"),
    private val atomicFiles: (File) -> AtomicFile = { AtomicFile(it) },
) : RechargePendingStore {
    private fun file(owner: SchoolStudentIdentity): AtomicFile = atomicFiles(File(directory,
        CardRechargeProtocol.hash(owner.accountId.value + ":" + owner.studentId) + ".enc"))
    @Synchronized override fun read(owner: SchoolStudentIdentity): RechargePending? = read(file(owner), owner)
    private fun read(file: AtomicFile, owner: SchoolStudentIdentity): RechargePending? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            val bytes = file.openRead().use { it.readBytes().also { bytes -> if(bytes.size > 32768) throw RechargeFailure("P-PENDING-STORE") } }
            val plain = crypto.decrypt(bytes)
            return try { decode(plain.toString(Charsets.UTF_8), owner) } finally { plain.fill(0) }
        } catch (error: RechargeFailure) { throw error }
        catch (_: Exception) { throw RechargeFailure("P-PENDING-STORE") }
    }
    @Synchronized override fun save(record: RechargePending) {
        if (!directory.exists() && !directory.mkdirs()) throw RechargeFailure("P-PENDING-STORE")
        val file = file(record.owner)
        val plain = encode(record).toByteArray(Charsets.UTF_8)
        try {
            val encrypted = crypto.encrypt(plain)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                // AtomicFile.finishWrite can log sync/rename failures and return normally.
                // Observe descriptor sync failures ourselves, then verify the committed base.
                stream.fd.sync()
                file.finishWrite(stream)
            }
            catch(error: Exception) { file.failWrite(stream); throw error }
            // Every field matters: later saves add orderId/payId to the SAME attempt.
            // A failed acknowledgement retains the previous marker; never delete it here.
            if (read(file, record.owner) != record) throw RechargeFailure("P-PENDING-STORE")
        } catch (_: Exception) { throw RechargeFailure("P-PENDING-STORE") }
        finally { plain.fill(0) }
    }
    @Synchronized override fun remove(record: RechargePending) {
        // An old query/cleanup must not remove a later order.
        if (read(record.owner)?.attemptId == record.attemptId) {
            file(record.owner).delete()
            if (file(record.owner).baseFile.exists()) throw RechargeFailure("P-PENDING-STORE")
        }
    }
    @Synchronized override fun forget(owner: SchoolStudentIdentity) {
        file(owner).delete()
        if(file(owner).baseFile.exists()) throw RechargeFailure("P-PENDING-STORE")
    }
    @Synchronized override fun clearAll() {
        directory.listFiles()?.forEach { if (!it.delete()) throw RechargeFailure("P-PENDING-STORE") }
    }
    internal companion object {
        fun encode(p: RechargePending) = buildJsonObject {
            put("version",1);put("accountId",p.owner.accountId.value);put("studentId",p.owner.studentId)
            put("generation",p.owner.credentialGeneration.value);put("attemptId",p.attemptId);put("yuan",p.yuan)
            p.orderId?.let { put("orderId",it) };p.payId?.let { put("payId",it) }
        }.toString()
        fun decode(raw: String, expected: SchoolStudentIdentity): RechargePending {
            val data=CardRechargeProtocol.json(raw)
            fun value(key:String)=CardRechargeProtocol.field(data,key)
            if (value("version")!="1" || value("accountId")!=expected.accountId.value || value("studentId")!=expected.studentId) throw RechargeFailure("P-ACCOUNT")
            val generation=value("generation").toLongOrNull()?.takeIf { it>0 } ?: throw RechargeFailure("P-PENDING-STORE")
            val attempt=value("attemptId").takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: throw RechargeFailure("P-PENDING-STORE")
            val order=value("orderId").ifEmpty { null };val pay=value("payId").ifEmpty { null }
            if (order!=null && !order.matches(Regex("[0-9]{8,40}")) || pay!=null && !pay.matches(Regex("[0-9]{1,8}"))) throw RechargeFailure("P-PENDING-STORE")
            return RechargePending(SchoolStudentIdentity(SchoolAccountId(value("accountId")),value("studentId"),AccountGeneration(generation)),attempt,CardRechargeProtocol.amount(value("yuan")),order,pay)
        }
    }
}
