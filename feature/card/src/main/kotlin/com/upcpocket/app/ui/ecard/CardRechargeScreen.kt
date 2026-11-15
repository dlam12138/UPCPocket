package com.upcpocket.app.ui.ecard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.cardrecharge.CardRechargeProtocol
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import com.upcpocket.app.platform.AccountMask
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Only the school account is used; normal flow is amount → confirmation → WeChat. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CardRechargeScreen(service: CardRechargeService, back:()->Unit, accountSettings:()->Unit) {
    val owner by service.studentIdentity.collectAsStateWithLifecycle()
    val account by service.accountState.collectAsStateWithLifecycle()
    val state by service.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<SchoolStudentIdentity,String>?>(null) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var returning by rememberSaveable { mutableStateOf(false) }
    var inputError by remember { mutableStateOf(false) }
    val available=owner!=null && !account.blocksPersonalSessions
    val visible=state.takeIf { available && it.owner==owner }
    LaunchedEffect(owner,account.blocksPersonalSessions) {
        confirmation=null;clearConfirmation=false;returning=false;amount="";inputError=false
        if(available) service.open()
    }
    DisposableEffect(lifecycle,service) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME && returning) {
                returning=false
                scope.launch { service.refresh() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar={ TopAppBar(title={Text("校园卡充值")},navigationIcon={ IconButton(onClick=back) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription="返回")
    } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text(owner?.studentId?.let(AccountMask::mask) ?: "请先设置学校账号")
                if(!available) Button(onClick=accountSettings) { Text("学校账号设置") }
                if(visible?.busy==true) LinearProgressIndicator(Modifier.fillMaxWidth())
                visible?.message?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
            }
            visible?.rules?.let { rules -> item {
                Text("学校充值范围 ¥ ${rules.minimum.stripTrailingZeros().toPlainString()}–${rules.maximum.stripTrailingZeros().toPlainString()}",style=MaterialTheme.typography.bodySmall)
                if(!rules.presetOnly) OutlinedTextField(amount,{amount=it;inputError=false},label={Text("充值金额（元）")},
                    supportingText={ if(inputError) Text("请输入符合学校规则的金额") else if(rules.integerOnly) Text("学校仅支持整数金额") },
                    isError=inputError,singleLine=true,enabled=!visible.busy&&!visible.recoveryRequired,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    rules.presets.forEach { preset ->
                        val value=preset.stripTrailingZeros().toPlainString()
                        FilterChip(selected=amount==value,onClick={amount=value;inputError=false},enabled=!visible.busy&&!visible.recoveryRequired,label={Text("¥ $value")})
                    }
                }
                if(!visible.recoveryRequired) Button(onClick={
                    val selected=runCatching { service.validateAmount(amount) }.getOrNull()
                    if(selected==null) inputError=true else owner?.let { confirmation=it to selected }
                },enabled=!visible.busy,modifier=Modifier.fillMaxWidth()) { Text("微信充值") }
            } }
            if(visible?.recoveryRequired==true) item {
                visible.pending?.let { Text("本次充值 ¥ ${it.yuan}") }
                OutlinedButton(onClick={scope.launch { service.refresh() }},enabled=!visible.busy) { Text("刷新支付状态") }
                TextButton(onClick={clearConfirmation=true},enabled=!visible.busy) { Text("已核对官方记录，处理未完成订单") }
            }
            if(available && visible?.rules==null && visible?.busy!=true && visible?.recoveryRequired!=true) item {
                OutlinedButton(onClick={scope.launch {service.open()}}) { Text("重新加载") }
                TextButton(onClick=accountSettings) { Text("学校账号设置") }
            }
            item { Text("支付完成后，按学校说明在 POS 刷卡写入卡账户。",style=MaterialTheme.typography.bodySmall) }
        }
    }
    confirmation?.let { selected -> AlertDialog(onDismissRequest={confirmation=null},title={Text("确认充值 ¥ ${selected.second}")},
        text={Text("充值到本人校园卡，接下来将唤起微信确认付款。")},
        confirmButton={TextButton(onClick={
            confirmation=null
            if(owner==selected.first && available) scope.launch {
                val handoff=service.submit(selected.second)
                coroutineContext.ensureActive()
                handoff?.consume()?.let { raw ->
                    try {
                        val safe=CardRechargeProtocol.wechatUri(raw)
                        val intent=Intent(Intent.ACTION_VIEW,Uri.parse(safe)).setPackage("com.tencent.mm")
                        val target=context.packageManager.resolveActivity(intent,0)?.activityInfo
                        if(target==null || target.packageName!="com.tencent.mm" || !target.exported) throw ActivityNotFoundException()
                        returning=true
                        context.startActivity(intent)
                    } catch(_:Exception) { returning=false;service.launchFailed() }
                }
            }
        }) {Text("确认并前往微信")}},dismissButton={TextButton(onClick={confirmation=null}){Text("取消")}}) }
    if(clearConfirmation) AlertDialog(onDismissRequest={clearConfirmation=false},title={Text("已核对学校与微信记录？")},
        text={Text("只有确认旧订单无需继续付款时再清除本机标记。此操作不会取消学校订单或退款，避免重复充值。")},
        confirmButton={TextButton(onClick={clearConfirmation=false;scope.launch {service.clearAfterVerification()}}){Text("已核对，清除标记")}},
        dismissButton={TextButton(onClick={clearConfirmation=false}){Text("返回核对")}})
}
