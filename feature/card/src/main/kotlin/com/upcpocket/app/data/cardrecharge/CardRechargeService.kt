package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.cardhistory.CardHistoryException
import com.upcpocket.app.data.cardhistory.CardHistoryFailure
import com.upcpocket.app.data.cardhistory.CardRechargeSession
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal enum class RechargeStatus { SUCCEEDED, FAILED, EXPIRED, UNKNOWN }
internal data class CardRechargeState(
    val owner: SchoolStudentIdentity? = null, val busy: Boolean = false,
    val rules: RechargeRules? = null, val pending: RechargePending? = null,
    val message: String? = null, val recoveryRequired: Boolean = false,
) { override fun toString() = "CardRechargeState([REDACTED])" }

/** A URI can be consumed only once, while its exact account and operation are still current. */
internal class RechargeHandoff(private val uri: String, private val valid: () -> Boolean) {
    private val consumed=AtomicBoolean()
    fun consume(): String? = if (consumed.compareAndSet(false,true) && valid()) CardRechargeProtocol.wechatUri(uri) else null
    override fun toString() = "RechargeHandoff([REDACTED])"
}

class CardRechargeService internal constructor(
    private val history: CardHistoryClient,
    private val store: RechargePendingStore,
    private val transport: RechargeTransport = RechargeHttpTransport(),
) {
    private val lock=Any()
    private var epoch=0L
    private var prepared: RechargePrepared?=null
    private val mutable=MutableStateFlow(CardRechargeState())
    internal val state=mutable.asStateFlow()
    val studentIdentity get()=history.studentIdentity
    val accountState get()=history.accountState
    private fun current(owner:SchoolStudentIdentity, generation:Long) = history.isCurrent(owner) && epoch==generation
    private fun check(owner:SchoolStudentIdentity,generation:Long) { if(!current(owner,generation)) throw RechargeFailure("P-ACCOUNT") }
    private fun api(session:CardRechargeSession,generation:Long) = CardRechargeApi(transport) {
        session.checkCurrent(); synchronized(lock) { current(session.owner,generation) }
    }
    private suspend fun <T> withSession(owner:SchoolStudentIdentity,generation:Long,block:suspend(CardRechargeSession)->T):T =
        history.withRechargeSession { session ->
            try { session.checkCurrent();check(owner,generation);block(session) }
            catch(error:RechargeFailure) {
                if(error.stage=="P-LOGIN") synchronized(lock) {
                    if(current(owner,generation)) session.expireIfCurrent()
                }
                throw error
            }
        }
    private fun begin(): Pair<SchoolStudentIdentity,Long>? = synchronized(lock) {
        val owner=history.studentIdentity.value ?: return null
        if(mutable.value.busy || !history.isCurrent(owner)) return null
        if(mutable.value.owner!=owner) { prepared=null;mutable.value=CardRechargeState(owner=owner) }
        mutable.value=mutable.value.copy(owner=owner,busy=true,message=null)
        owner to epoch
    }
    private fun publish(owner:SchoolStudentIdentity,generation:Long, change:(CardRechargeState)->CardRechargeState) = synchronized(lock) {
        check(owner,generation); mutable.value=change(mutable.value)
    }
    private fun failed(owner:SchoolStudentIdentity,generation:Long,error:Exception) {
        synchronized(lock) {
            if(!current(owner,generation)) return
            val code=(error as? RechargeFailure)?.stage
            val text=when {
                error is CardHistoryException -> when(error.reason) {
                    CardHistoryFailure.INTERACTION_REQUIRED -> "学校要求额外验证，请先完成学校账号登录。"
                    CardHistoryFailure.INVALID_CREDENTIALS -> "学校未接受账号密码，请到学校账号设置核对。"
                    else -> "学校登录未完成，请稍后重试。"
                }
                code=="P-AMOUNT" -> "金额不符合学校充值规则，请重新选择。"
                code=="P-FEE-CONFIG" -> "学校充值额度暂时不可用，请稍后重试。"
                code=="P-CARD-BLOCKED" -> "校园卡状态暂不允许充值，请在学校页面核对。"
                mutable.value.pending!=null || mutable.value.recoveryRequired -> "订单或付款状态尚未确认，请先刷新状态并核对微信账单。"
                else -> "暂时无法完成充值，请稍后重试。"
            }
            mutable.value=mutable.value.copy(busy=false,message=text,recoveryRequired=mutable.value.recoveryRequired || code=="P-PENDING-STORE")
        }
    }
    internal suspend fun open() {
        val (owner,generation)=begin() ?: return
        try {
            withContext(Dispatchers.IO) {
                withSession(owner,generation) { session ->
                    check(owner,generation)
                    val pending=store.read(owner)
                    if(pending!=null) publish(owner,generation) { it.copy(pending=pending,recoveryRequired=true) }
                    val config=api(session,generation).prepare(session)
                    synchronized(lock) { check(owner,generation);prepared=config }
                    publish(owner,generation) { it.copy(busy=false,rules=config.rules,message=if(pending!=null) "上次订单需要确认，正在查询状态。" else null) }
                }
            }
            if(mutable.value.pending!=null) refresh()
        } catch(cancelled:CancellationException) {
            synchronized(lock) { if(current(owner,generation)) mutable.value=mutable.value.copy(busy=false) }
            throw cancelled
        } catch(error:Exception) { failed(owner,generation,error) }
    }
    internal fun validateAmount(raw:String): String = synchronized(lock) {
        (prepared ?: throw RechargeFailure("P-CONFIG")).rules.amount(raw)
    }
    internal suspend fun submit(raw:String): RechargeHandoff? {
        val (owner,generation)=begin() ?: return null
        try {
            return withContext(NonCancellable + Dispatchers.IO) {
                withSession(owner,generation) { session ->
                    check(owner,generation)
                    // Refresh read-only limits before the sole order request; stale displayed limits never authorize a larger order.
                    val config=api(session,generation).prepare(session)
                    synchronized(lock) { check(owner,generation);prepared=config;mutable.value=mutable.value.copy(rules=config.rules) }
                    val amount=config.rules.amount(raw)
                    var pending=RechargePending(owner,UUID.randomUUID().toString(),amount)
                    synchronized(lock) {
                        check(owner,generation)
                        if(store.read(owner)!=null) throw RechargeFailure("P-PENDING")
                        store.save(pending)
                        prepared=config
                        mutable.value=mutable.value.copy(rules=config.rules,pending=pending,recoveryRequired=true)
                    }
                    val client=api(session,generation)
                    val order=client.create(session,config,amount)
                    synchronized(lock) { check(owner,generation);pending=pending.copy(orderId=order);store.save(pending);mutable.value=mutable.value.copy(pending=pending) }
                    val uri=client.pay(session,config,pending) { method -> synchronized(lock) {
                        check(owner,generation);pending=pending.copy(payId=method);store.save(pending);mutable.value=mutable.value.copy(pending=pending)
                    } }
                    publish(owner,generation) { it.copy(busy=false,message="返回后将查询学校订单状态。") }
                    RechargeHandoff(uri) { synchronized(lock) { current(owner,generation) && mutable.value.pending?.attemptId==pending.attemptId && runCatching { session.checkCurrent() }.isSuccess } }
                }
            }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { failed(owner,generation,error);return null }
        finally { synchronized(lock) { if(current(owner,generation)) mutable.value=mutable.value.copy(busy=false) } }
    }
    internal suspend fun refresh() {
        val (owner,generation)=begin() ?: return
        try {
            withContext(Dispatchers.IO) {
                withSession(owner,generation) { session ->
                    val pending=synchronized(lock) { check(owner,generation);store.read(owner) } ?: run {
                        publish(owner,generation) { it.copy(busy=false,pending=null,recoveryRequired=false) };return@withSession
                    }
                    if(pending.orderId==null) throw RechargeFailure("P-ORDER-UNKNOWN")
                    val result=api(session,generation).status(session,pending)
                    synchronized(lock) {
                        check(owner,generation)
                        val terminal=result==RechargeStatus.SUCCEEDED
                        if(terminal) store.remove(pending)
                        mutable.value=mutable.value.copy(busy=false,pending=if(terminal)null else pending,recoveryRequired=!terminal,message=when(result) {
                            RechargeStatus.SUCCEEDED -> "充值支付成功，按学校说明在 POS 刷卡写入卡账户。"
                            RechargeStatus.FAILED -> "学校显示交易失败，请核对微信账单。"
                            RechargeStatus.EXPIRED -> "订单已超时，请核对微信账单。"
                            RechargeStatus.UNKNOWN -> "学校尚未确认结果，请稍后刷新状态。"
                        })
                    }
                }
            }
        } catch(cancelled:CancellationException) { synchronized(lock) { if(current(owner,generation)) mutable.value=mutable.value.copy(busy=false) };throw cancelled }
        catch(error:Exception) { failed(owner,generation,error) }
    }
    internal suspend fun clearAfterVerification() {
        val (owner,generation)=begin() ?: return
        try { withContext(Dispatchers.IO) {
            synchronized(lock) { check(owner,generation);store.forget(owner);mutable.value=mutable.value.copy(busy=false,pending=null,recoveryRequired=false,message=null) }
        } } catch(error:Exception) { failed(owner,generation,error) }
    }
    internal fun launchFailed() { synchronized(lock) { mutable.value=mutable.value.copy(message="未能唤起微信，请检查微信是否安装，并先核对本次订单状态。") } }
    suspend fun invalidateForAccountChange(scope:AccountDataScope): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            epoch++; prepared=null;mutable.value=CardRechargeState()
            if(scope==AccountDataScope.PERSONAL_DATA) runCatching { store.clearAll() }.isSuccess else true
        }
    }
    companion object {
        /** Also used when the plugin was removed or quarantined before account deletion. */
        fun clearStoredData(noBackupDirectory:File): Boolean = runCatching {
            EncryptedRechargePendingStore(File(noBackupDirectory,"card-recharge")).clearAll()
        }.isSuccess
    }

}
