package com.upcpocket.app.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.ManagedDevice
import com.upcpocket.app.account.api.ManagedDeviceListResult
import com.upcpocket.app.account.api.SchoolAccountController
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.ui.components.CampusAlertDialog
import com.upcpocket.app.ui.components.CampusButton
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassIconButton as IconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pocket 校方托管账号设置。密码不会落盘；应用自有仓库与网络层只用可清零 CharArray/ByteArray，
 * 并在 finally 中清零。Compose 输入控件、Android IME 与 JVM 内部仍可能短暂产生应用无法主动擦除的
 * 临时文本副本，因此 UI 层不宣称严格不存在不可变 String 副本。密码状态使用非保存型 TextFieldState，
 * 避免进入 Android SavedState。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolAccountScreen(
    accounts: SchoolAccountController,
    onBack: () -> Unit,
) {
    val state by accounts.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val bindAccountFocus = remember { FocusRequester() }
    val bindPasswordFocus = remember { FocusRequester() }
    val updateAccountFocus = remember { FocusRequester() }
    val updatePasswordFocus = remember { FocusRequester() }
    // Do not use rememberTextFieldState here: it saves/restores text through rememberSaveable.
    val bindPasswordState = remember { TextFieldState() }
    val updatePasswordState = remember { TextFieldState() }
    var bindLoginName by remember { mutableStateOf("") }
    var updateLoginName by remember { mutableStateOf("") }
    var acceptCustody by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ManagedDevice>?>(null) }
    var showUnbindDialog by remember { mutableStateOf(false) }

    fun clearPasswords() {
        bindPasswordState.clearAndForgetUndo()
        updatePasswordState.clearAndForgetUndo()
    }

    LaunchedEffect(Unit) { accounts.restore() }

    DisposableEffect(view) {
        val activity = context.findActivity()
        val window = activity?.window
        val oldFlags = window?.attributes?.flags ?: 0
        val decor = window?.decorView
        val oldAutofill = decor?.importantForAutofill
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        decor?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            clearPasswords()
            bindLoginName = ""
            updateLoginName = ""
            decor?.importantForAutofill = oldAutofill ?: View.IMPORTANT_FOR_AUTOFILL_AUTO
            if (window != null && oldFlags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun runOperation(
        operation: suspend () -> SchoolAccountWriteResult,
        success: String,
        clearTemporary: () -> Unit = {},
    ) {
        if (busy) {
            clearTemporary()
            return
        }
        busy = true
        message = null
        messageIsError = false
        val job = scope.launch {
            val result = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE)
            } finally {
                busy = false
                clearTemporary()
            }
            when (result) {
                SchoolAccountWriteResult.Success -> {
                    message = success
                    messageIsError = false
                    devices = null
                }
                is SchoolAccountWriteResult.Failure -> {
                    val currentState = accounts.state.value
                    message = when (currentState.status) {
                        SchoolAccountStatus.UNBIND_PENDING,
                        SchoolAccountStatus.SIGN_OUT_PENDING,
                        -> currentState.summaryText()
                        else -> result.code.userMessage()
                    }
                    messageIsError = true
                }
            }
        }
        job.invokeOnCompletion { clearTemporary() }
    }

    fun submitBind() {
        if (busy) return
        if (!acceptCustody) {
            message = "请先阅读说明并同意由校方服务代管学校账号。"
            messageIsError = true
            return
        }
        if (bindLoginName.isBlank() || bindPasswordState.text.isEmpty()) {
            message = "请输入学校账号和密码。"
            messageIsError = true
            return
        }
        val login = bindLoginName.trim()
        val secret = bindPasswordState.text.copyToCharArray()
        bindLoginName = ""
        bindPasswordState.clearAndForgetUndo()
        keyboardController?.hide()
        runOperation(
            operation = { accounts.bind(login, secret, acceptCustody = true) },
            success = "校方托管账号已绑定。",
            clearTemporary = { secret.fill('\u0000') },
        )
    }

    fun submitPasswordUpdate() {
        if (busy) return
        if (updateLoginName.isBlank() || updatePasswordState.text.isEmpty()) {
            message = "请输入学校账号和新密码。"
            messageIsError = true
            return
        }
        val login = updateLoginName.trim()
        val secret = updatePasswordState.text.copyToCharArray()
        updateLoginName = ""
        updatePasswordState.clearAndForgetUndo()
        keyboardController?.hide()
        runOperation(
            operation = { accounts.updateManagedCredential(login, secret) },
            success = "密码更新请求已完成，本机需要重新绑定。",
            clearTemporary = { secret.fill('\u0000') },
        )
    }

    fun loadDevices() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = try {
                accounts.listDevices()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ManagedDeviceListResult.Failure(SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE)
            } finally {
                busy = false
            }
            when (result) {
                is ManagedDeviceListResult.Success -> devices = result.devices
                is ManagedDeviceListResult.Failure -> {
                    message = result.code.userMessage()
                    messageIsError = true
                }
            }
        }
    }

    Scaffold(
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        topBar = {
            CampusTopAppBar(
                title = { Text("学校账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.summaryText(),
                        color = if (state.errorCode != null || state.cleanupRequired ||
                            state.status == SchoolAccountStatus.UNBIND_PENDING ||
                            state.status == SchoolAccountStatus.SIGN_OUT_PENDING
                        ) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("account_status"),
                    )
                    if (state.status == SchoolAccountStatus.MANAGED_ACTIVE) {
                        Text("本机设备：${state.deviceId ?: "已绑定"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "绑定后，学校服务会按本机授权代取只读课表、校园卡余额和成绩。学校密码不会保存在手机；解除绑定会删除校方托管账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.cleanupRequired) {
                CampusButton(
                    onClick = {
                        runOperation(accounts::retryCleanup, "清理完成。")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("继续清理", modifier = Modifier.padding(start = 8.dp))
                }
            }

            when (state.status) {
                SchoolAccountStatus.UNBIND_PENDING -> CampusButton(
                    onClick = {
                        runOperation({ accounts.unbind(confirmed = true) }, "校方托管账号已删除。")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重试解除托管") }

                SchoolAccountStatus.SIGN_OUT_PENDING -> CampusButton(
                    onClick = { runOperation(accounts::signOut, "已退出本机。") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重试退出本机") }

                else -> Unit
            }

            val pendingRevocation = state.status == SchoolAccountStatus.UNBIND_PENDING ||
                state.status == SchoolAccountStatus.SIGN_OUT_PENDING

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("绑定或切换托管账号", style = MaterialTheme.typography.titleSmall)
                    if (pendingRevocation) {
                        Text(
                            "当前存在待确认的解绑或退出请求，请先完成上方重试操作后再重新绑定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedAccountField(
                        value = bindLoginName,
                        onValueChange = { bindLoginName = it },
                        label = "学校账号",
                        enabled = !busy && !pendingRevocation,
                        focusRequester = bindAccountFocus,
                        testTag = "bind_account_input",
                        imeAction = ImeAction.Next,
                        onNext = { bindPasswordFocus.requestFocus() },
                    )
                    OutlinedPasswordField(
                        state = bindPasswordState,
                        label = "学校密码",
                        enabled = !busy && !pendingRevocation,
                        focusRequester = bindPasswordFocus,
                        testTag = "bind_password_input",
                        imeAction = ImeAction.Done,
                        onDone = ::submitBind,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = acceptCustody,
                            onCheckedChange = { acceptCustody = it },
                            enabled = !busy && !pendingRevocation,
                            modifier = Modifier.testTag("custody_consent"),
                        )
                        Text("我同意由校方服务托管学校账号，用于本页说明的只读功能。")
                    }
                    CampusButton(
                        onClick = ::submitBind,
                        enabled = !busy && !state.cleanupRequired && !pendingRevocation,
                        modifier = Modifier.fillMaxWidth().testTag("bind_account"),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Lock, contentDescription = null)
                        Text("绑定登录", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("更新校方托管密码", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "此操作直接更新校方托管密码，不使用本机 Pocket 会话。完成后所有设备下线，请重新绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedAccountField(
                        value = updateLoginName,
                        onValueChange = { updateLoginName = it },
                        label = "学校账号",
                        enabled = !busy && !pendingRevocation,
                        focusRequester = updateAccountFocus,
                        testTag = "update_account_input",
                        imeAction = ImeAction.Next,
                        onNext = { updatePasswordFocus.requestFocus() },
                    )
                    OutlinedPasswordField(
                        state = updatePasswordState,
                        label = "新密码",
                        enabled = !busy && !pendingRevocation,
                        focusRequester = updatePasswordFocus,
                        testTag = "update_password_input",
                        imeAction = ImeAction.Done,
                        onDone = ::submitPasswordUpdate,
                    )
                    CampusOutlinedButton(
                        onClick = ::submitPasswordUpdate,
                        enabled = !busy && !pendingRevocation,
                        modifier = Modifier.fillMaxWidth().testTag("update_password"),
                    ) { Text("更新密码") }
                }
            }

            if (state.isManagedActive) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("活跃设备", style = MaterialTheme.typography.titleSmall)
                        CampusOutlinedButton(
                            onClick = ::loadDevices,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "正在读取…" else "查看设备") }
                        devices?.forEach { device ->
                            DeviceRow(
                                device = device,
                                isCurrent = device.deviceId == state.deviceId,
                                enabled = !busy,
                                onRevoke = {
                                    runOperation(
                                        operation = { accounts.revokeDevice(device.deviceId) },
                                        success = if (device.deviceId == state.deviceId) {
                                            "本机已退出。"
                                        } else "设备已撤销。",
                                    )
                                },
                            )
                        }
                        CampusOutlinedButton(
                            onClick = {
                                runOperation(accounts::signOut, "已退出本机。")
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("退出本机") }
                        CampusOutlinedButton(
                            onClick = { showUnbindDialog = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("解除托管并删除账号") }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("account_message"),
                )
            }
        }
    }

    if (showUnbindDialog) {
        CampusAlertDialog(
            onDismissRequest = { showUnbindDialog = false },
            title = { Text("解除托管并删除账号？") },
            text = { Text("这会先清除此设备的个人缓存和组件内容，再请求删除校方托管账号。其他设备也会失去访问权限。") },
            confirmButton = {
                CampusTextButton(onClick = {
                    showUnbindDialog = false
                    runOperation({ accounts.unbind(confirmed = true) }, "校方托管账号已删除。")
                }) { Text("确认删除") }
            },
            dismissButton = {
                CampusTextButton(onClick = { showUnbindDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OutlinedAccountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    testTag: String,
    imeAction: ImeAction,
    onNext: () -> Unit,
) {
    com.upcpocket.app.ui.components.GlassOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(testTag),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun OutlinedPasswordField(
    state: TextFieldState,
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    testTag: String,
    imeAction: ImeAction,
    onDone: () -> Unit,
) {
    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        onKeyboardAction = { performDefaultAction ->
            onDone()
            performDefaultAction()
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(testTag),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun TextFieldState.clearAndForgetUndo() {
    clearText()
    undoState.clearHistory()
}

private fun CharSequence.copyToCharArray(): CharArray = CharArray(length) { index -> this[index] }

@Composable
private fun DeviceRow(
    device: ManagedDevice,
    isCurrent: Boolean,
    enabled: Boolean,
    onRevoke: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${device.platform} · ${device.kind}${if (isCurrent) " · 本机" else ""}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("有效期至 ${device.expiresAtEpochMillis.takeIf { it > 0 }?.let(::deviceExpiryText) ?: "未知"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CampusTextButton(onClick = onRevoke, enabled = enabled) {
            Text(if (isCurrent) "撤销本机" else "撤销此设备")
        }
    }
}

private fun deviceExpiryText(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime().let { "%04d-%02d-%02d %02d:%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute) }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
