package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import com.upcpocket.app.platform.AccountMask
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** The recharge button authorizes one order; actual payment is confirmed inside WeChat. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CardRechargeScreen(
    service: CardRechargeService,
    back: () -> Unit,
    accountSettings: () -> Unit,
    wechatLauncher: CardRechargeWeChatLauncher? = null,
) {
    val owner by service.studentIdentity.collectAsStateWithLifecycle()
    val account by service.accountState.collectAsStateWithLifecycle()
    val state by service.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wechat = wechatLauncher ?: remember(context) { AndroidCardRechargeWeChatLauncher(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var clearConfirmation by remember { mutableStateOf(false) }
    var returning by rememberSaveable { mutableStateOf(false) }
    var inputError by remember { mutableStateOf(false) }
    var launchError by remember { mutableStateOf<String?>(null) }
    val available = owner != null && !account.blocksPersonalSessions
    val visible = state.takeIf { available && it.owner == owner }
    val selectedAmount = visible?.rules?.let { rules -> runCatching { rules.amount(amount) }.getOrNull() }

    LaunchedEffect(owner, account.blocksPersonalSessions) {
        clearConfirmation = false
        returning = false
        amount = ""
        inputError = false
        launchError = null
        if (available) service.open()
    }
    DisposableEffect(lifecycle, service) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && returning) {
                returning = false
                scope.launch { service.refresh() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = com.upcpocket.app.ui.liquid.liquidScaffoldColor(),
        topBar = {
            com.upcpocket.app.ui.liquid.LiquidTopAppBar(
                title = { Text("校园卡充值") },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(owner?.studentId?.let { "本人校园卡 · ${AccountMask.mask(it)}" } ?: "请先设置学校账号")
                if (!available) Button(onClick = accountSettings) { Text("学校账号设置") }
                if (visible?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                visible?.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            visible?.rules?.let { rules -> item {
                Text(
                    "充值范围 ¥ ${rules.minimum.stripTrailingZeros().toPlainString()}–${rules.maximum.stripTrailingZeros().toPlainString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!rules.presetOnly) OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; inputError = false },
                    label = { Text("充值金额（元）") },
                    supportingText = {
                        if (inputError) Text("请输入符合学校规则的金额")
                        else if (rules.integerOnly) Text("学校仅支持整数金额")
                    },
                    isError = inputError,
                    singleLine = true,
                    enabled = !visible.busy && !visible.recoveryRequired,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("recharge_amount"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rules.presets.forEach { preset ->
                        val value = preset.stripTrailingZeros().toPlainString()
                        com.upcpocket.app.ui.liquid.LiquidFilterChip(
                            selected = amount == value,
                            onClick = { amount = value; inputError = false },
                            enabled = !visible.busy && !visible.recoveryRequired,
                            label = { Text("¥ $value") },
                        )
                    }
                }
                if (!visible.recoveryRequired) com.upcpocket.app.ui.liquid.LiquidButton(
                    onClick = {
                        val selectedOwner = owner
                        if (selectedAmount == null) {
                            inputError = true
                        } else if (!wechat.isAvailable()) {
                            launchError = "未找到可用的微信，请安装或启用后重试。"
                        } else if (selectedOwner != null && available) {
                            launchError = null
                            scope.launch {
                                val handoff = service.submit(selectedAmount, selectedOwner)
                                coroutineContext.ensureActive()
                                handoff?.consume()?.let { uri ->
                                    returning = wechat.launch(uri)
                                    if (!returning) service.launchFailed()
                                }
                            }
                        }
                    },
                    enabled = !visible.busy,
                    modifier = Modifier.fillMaxWidth().testTag("recharge_submit"),
                ) { Text(selectedAmount?.let { "微信充值 ¥ $it" } ?: "微信充值") }
            } }
            if (visible?.recoveryRequired == true) item {
                visible.pending?.let { Text("本次充值 ¥ ${it.yuan}") }
                OutlinedButton(onClick = { scope.launch { service.refresh() } }, enabled = !visible.busy) {
                    Text("刷新支付状态")
                }
                TextButton(onClick = { clearConfirmation = true }, enabled = !visible.busy) {
                    Text("已核对官方记录，处理未完成订单")
                }
            }
            if (available && visible?.rules == null && visible?.busy != true && visible?.recoveryRequired != true) item {
                OutlinedButton(onClick = { scope.launch { service.open() } }) { Text("重新加载") }
                TextButton(onClick = accountSettings) { Text("学校账号设置") }
            }
        }
    }
    if (clearConfirmation) com.upcpocket.app.ui.liquid.LiquidDialog(
        onDismissRequest = { clearConfirmation = false },
        title = { Text("已核对学校与微信记录？") },
        text = { Text("只有确认旧订单无需继续付款时再清除本机标记。此操作不会取消学校订单或退款，避免重复充值。") },
        confirmButton = {
            TextButton(onClick = { clearConfirmation = false; scope.launch { service.clearAfterVerification() } }) {
                Text("已核对，清除标记")
            }
        },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("返回核对") } },
    )
}
