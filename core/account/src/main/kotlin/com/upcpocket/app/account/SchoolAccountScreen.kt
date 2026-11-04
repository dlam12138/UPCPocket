package com.upcpocket.app.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolAccountController
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.platform.AccountMask
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.launch

/**
 * 「首页 → 设置 → 学校账号」最小设置页。
 *
 * 这里只做本机凭据的保存、修改与删除，不发起任何学校网络请求，也不把
 * “已保存”表述成“已登录”。密码输入只存在于当前 Compose 组合的 `remember`
 * 中，不使用 rememberSaveable/SavedStateHandle；保存尝试和离页时立即清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolAccountScreen(
    accounts: SchoolAccountController,
    onBack: () -> Unit,
    legacyStudentIdHint: String? = null,
) {
    val state by accounts.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var accountInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun clearSecrets() {
        passwordInput = ""
        confirmInput = ""
    }

    LaunchedEffect(Unit) { accounts.restore() }

    // 密码字段不能出现在截图/录屏里；整个账号页在可见期间加 FLAG_SECURE。
    DisposableEffect(view) {
        val activity = context.findActivity()
        val window = activity?.window
        val oldFlags = window?.attributes?.flags ?: 0
        val decor = window?.decorView
        val oldAutofill = decor?.importantForAutofill
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        decor?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            clearSecrets()
            accountInput = ""
            decor?.importantForAutofill = oldAutofill
                ?: View.IMPORTANT_FOR_AUTOFILL_AUTO
            if (window != null &&
                (oldFlags and WindowManager.LayoutParams.FLAG_SECURE) == 0
            ) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun submit() {
        if (busy) return
        message = null
        messageIsError = false
        val currentSaved = state.status == SchoolAccountStatus.SAVED_UNVERIFIED
        val accountToUse = accountInput.trim().takeIf { it.isNotEmpty() }
        if (accountToUse == null && !currentSaved) {
            clearSecrets()
            message = "请输入学校账号。"
            messageIsError = true
            return
        }
        if (passwordInput.isEmpty()) {
            clearSecrets()
            message = "请输入密码。"
            messageIsError = true
            return
        }
        if (passwordInput != confirmInput) {
            message = "两次输入的密码不一致。"
            messageIsError = true
            clearSecrets()
            return
        }
        val secret = passwordInput.toCharArray()
        accountInput = ""
        clearSecrets()
        busy = true
        scope.launch {
            val result = accounts.save(accountToUse, secret)
            busy = false
            when (result) {
                SchoolAccountWriteResult.Success -> {
                    message = "已保存，尚未验证。"
                    messageIsError = false
                }
                is SchoolAccountWriteResult.Failure -> {
                    message = result.code.userMessage()
                    messageIsError = true
                }
            }
        }
    }

    fun delete() {
        if (busy) return
        busy = true
        message = null
        messageIsError = false
        scope.launch {
            val result = accounts.delete()
            busy = false
            when (result) {
                SchoolAccountWriteResult.Success -> {
                    message = "本机学校账号与关联个人会话/缓存已删除。"
                    messageIsError = false
                }
                is SchoolAccountWriteResult.Failure -> {
                    message = result.code.userMessage()
                    messageIsError = true
                }
            }
        }
    }

    fun retryCleanup() {
        if (busy) return
        busy = true
        message = null
        messageIsError = false
        scope.launch {
            val result = accounts.retryCleanup()
            busy = false
            when (result) {
                SchoolAccountWriteResult.Success -> {
                    message = "旧账号数据清理已完成。"
                    messageIsError = false
                }
                is SchoolAccountWriteResult.Failure -> {
                    message = result.code.userMessage()
                    messageIsError = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("学校账号")
                        Text(
                            "本机加密 · 保存不等于认证通过",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "本机凭据",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = state.summaryText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.errorCode != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.testTag("account_status"),
                    )
                    if (state.status == SchoolAccountStatus.SAVED_UNVERIFIED) {
                        Text(
                            text = "凭据版本：第 ${state.generation.value} 版。保存只代表密文已写入本机，" +
                                "不代表学校认证通过。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (state.studentIdAvailable) {
                                "余额查询学号：${state.maskedStudentId.orEmpty()}。仅由登录名整串识别，" +
                                    "尚未经过学校身份验证。"
                            } else {
                                "当前账号登录名不是 8–16 位数字学号，余额查询不可用；" +
                                    "不会猜测或截取其他数字。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("account_student_binding"),
                        )
                    }
                    state.errorCode?.let {
                        Text(
                            text = it.userMessage(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (!legacyStudentIdHint.isNullOrBlank()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("旧版余额学号提示", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "检测到旧版余额学号 ${AccountMask.mask(legacyStudentIdHint.trim())}。" +
                                "它只用于提示，不会自动创建账号或绑定余额；保存账号后，余额只按" +
                                "账号登录名中完整有效的本人学号查询。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("legacy_student_hint"),
                        )
                    }
                }
            }

            if (state.cleanupRequired) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("旧账号清理未完成", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "完成前不会启用新账号，也不会写入新的凭据版本。清理动作可重复执行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { retryCleanup() },
                            enabled = !busy,
                            modifier = Modifier.testTag("retry_cleanup"),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text("继续清理", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            Text(
                text = "输入学校账号与密码",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )

            OutlinedTextField(
                value = accountInput,
                onValueChange = { accountInput = it },
                label = {
                    Text(
                        if (state.status == SchoolAccountStatus.SAVED_UNVERIFIED) {
                            "学校账号（留空则只更新当前账号密码）"
                        } else {
                            "学校账号"
                        },
                    )
                },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account_input"),
            )

            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it },
                label = { Text("密码") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                supportingText = { Text("仅用于本机加密保存，不会在本页发起登录。") },
                modifier = Modifier.fillMaxWidth().testTag("password_input"),
            )

            OutlinedTextField(
                value = confirmInput,
                onValueChange = { confirmInput = it },
                label = { Text("确认密码") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().testTag("confirm_input"),
            )

            if (message != null) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.testTag("account_message"),
                )
            }

            Button(
                onClick = { submit() },
                enabled = !busy && !state.cleanupRequired,
                modifier = Modifier.fillMaxWidth().testTag("save_account"),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                }
                Text(
                    text = if (state.isCredentialSaved) "保存新密码" else "加密保存到本机",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = "密码使用 Android Keystore 中独立密钥的 AES-GCM 加密，密文写入应用私有目录；" +
                    "不写明文、不进入系统备份。保存成功后输入框会清空，不显示已保存密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.status == SchoolAccountStatus.SAVED_UNVERIFIED ||
                state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION
            ) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !busy && !state.cleanupRequired,
                    modifier = Modifier.fillMaxWidth().testTag("delete_account"),
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Text("删除本机学校账号", modifier = Modifier.padding(start = 8.dp))
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账号如何使用", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "校园卡余额只按当前账号登录名中完整有效的本人学号查询，不使用独立输入框；" +
                            "登录名不是 8–16 位数字时明确不可用。课表与图书馆在进入对应页面、由你主动操作时，" +
                            "才通过学校正常 CAS 使用这份账号；验证码或二次验证会转到学校自己的网页登录。" +
                            "保存成功只代表密文写入本机，不代表任何业务已登录或学校身份已验证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "删除账号会删除加密账密、关联业务会话与能确认归属的个人缓存，但不会删除专注历史、" +
                            "主题、公开教室数据或独立寝室配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除本机学校账号？") },
            text = {
                Text(
                    "将删除加密账密、关联业务会话与能确认归属的个人缓存，并刷新组件。" +
                        "不会删除专注历史、主题、公开教室或寝室配置。删除后不可恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        delete()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
