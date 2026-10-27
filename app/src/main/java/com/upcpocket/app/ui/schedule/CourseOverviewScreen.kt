package com.upcpocket.app.ui.schedule

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.core.ScheduleText
import com.upcpocket.app.domain.Course
import com.upcpocket.app.ui.components.GlassCard

/**
 * 课程总览：该学期要上的课**每行一门**，点进去看详情。
 *
 * 分成两组：
 *  - 「我的课程」：自己手动补录的课程（[com.upcpocket.app.domain.PlanKind.COURSE]）；
 *  - 「演示课程」：演示模式开着时的虚构课程，明确分组、不可编辑。
 *
 * 上游这里只有教务课程；本原型没有远端，所以"我的课程"就是用户自己补的那些。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseOverviewScreen(
    semesterId: Int,
    onBack: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 自己的 VM 实例也要先加载学期列表，之后再切到指定学期
    LaunchedEffect(state.semesters, semesterId) {
        if (state.semesters.isNotEmpty() && state.selectedSemester?.id != semesterId) {
            vm.selectSemester(semesterId)
        }
    }

    val courses = state.courses.sortedBy { it.nameZh }
    val demo = state.demoCourses.sortedBy { it.nameZh }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程总览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = buildString {
                    append("我的课程 ${courses.size} 门")
                    if (demo.isNotEmpty()) append(" · 演示 ${demo.size} 门（虚构，勿当真）")
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(courses, key = { it.key }) { course ->
                    CourseCard(course) { vm.openDetail(course, demo = false) }
                }
                if (demo.isNotEmpty()) {
                    item(key = "demo-header") {
                        Text(
                            text = "演示课程（虚构，关掉演示后消失）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                        )
                    }
                    items(demo, key = { "demo:${it.key}" }) { course ->
                        CourseCard(course, badge = "演示") { vm.openDetail(course, demo = true) }
                    }
                }
            }
        }
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = vm::closeDetail,
            overrides = state.detailOverrides,
            onApplyEdits = if (state.detailIsDemo) null else vm::applyDisplayEdits,
            onResetOverrides = if (state.detailIsDemo) null else vm::resetDisplayOverrides,
            demo = state.detailIsDemo,
        )
    }
}

@Composable
private fun CourseCard(course: Course, badge: String? = null, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.nameZh,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (course.teachers.isNotEmpty()) {
                Text(
                    text = course.teachers.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = ScheduleText.timeSlots(course.slots),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = ScheduleText.compressWeeks(course.weeks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}
