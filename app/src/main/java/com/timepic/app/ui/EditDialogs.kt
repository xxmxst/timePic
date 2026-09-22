package com.timepic.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timepic.app.TimePicState
import com.timepic.app.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun AddressDialog(
    state: TimePicState, onDismiss: () -> Unit, onSearch: (String) -> Unit,
    onQueryChanged: () -> Unit, onManual: (String) -> Unit,
    onSelect: (Place) -> Unit, onLocation: () -> Unit, onSettings: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(state.config.place.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("拍摄地点") },
        text = {
            Column {
                Text("输入地区、街道或详细地址，也可以使用当前位置。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(160); onQueryChanged() },
                    label = { Text("地址文字") }, minLines = 2, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    supportingText = { Text("${text.length}/160") }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onLocation) {
                        Icon(Icons.Outlined.MyLocation, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp)); Text("当前位置")
                    }
                    TextButton(onClick = { onSearch(text) }, enabled = text.isNotBlank() && !state.searchBusy) {
                        Text("搜索地点")
                    }
                }
                if (state.searchBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在查询系统地址服务…", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
                state.searchMessage?.let { Text(it, color = Muted, fontSize = 12.sp) }
                LazyColumn(Modifier.heightIn(max = 190.dp)) {
                    items(state.searchResults) { place ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(place) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp), tint = Gold)
                            Text(place.text, Modifier.padding(start = 8.dp), fontSize = 13.sp)
                        }
                        HorizontalDivider(color = Muted.copy(alpha = .15f))
                    }
                }
                if (!state.searchBusy && state.searchResults.isEmpty() && state.searchMessage == null) {
                    Text("搜索结果由手机系统提供；无需搜索也能直接填写地址。", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = onSettings) { Text("定位权限设置", fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onManual(text) }) { Text(if (text.isBlank()) "清除地址" else "使用此地址") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun TimeDialog(timestamp: Long, mode: TimeMode, onDismiss: () -> Unit, onConfirm: (Long?) -> Unit) {
    val context = LocalContext.current
    val initial = remember { Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()) }
    var epochDay by rememberSaveable { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var hour by rememberSaveable { mutableStateOf(initial.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable { mutableStateOf(initial.minute.toString().padStart(2, '0')) }
    var second by rememberSaveable { mutableStateOf(initial.second.toString().padStart(2, '0')) }
    val date = LocalDate.ofEpochDay(epochDay)
    val valid = hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59 && second.toIntOrNull() in 0..59
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("水印时间") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (mode == TimeMode.LIVE) "当前模式：实时" else "当前模式：固定时间", color = Gold, fontSize = 12.sp)
                Text("固定后，连续拍照将使用同一个时间。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            epochDay = LocalDate.of(y, m + 1, d).toEpochDay()
                        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }
                ) { Text("${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(hour, { hour = it }, "时", Modifier.weight(1f))
                    NumberField(minute, { minute = it }, "分", Modifier.weight(1f))
                    NumberField(second, { second = it }, "秒", Modifier.weight(1f))
                }
                if (!valid) Text("请输入 0–23 时、0–59 分和秒", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                TextButton(onClick = { onConfirm(null) }, modifier = Modifier.padding(top = 12.dp)) { Text("恢复实时") }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = {
            onConfirm(date.atTime(LocalTime.of(hour.toInt(), minute.toInt(), second.toInt()))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }) { Text("使用固定时间") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NumberField(value: String, change: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) change(it) },
        modifier = modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
fun NoteDialog(note: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(note) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("水印备注") },
        text = {
            OutlinedTextField(text, { text = it.take(100) }, minLines = 3, maxLines = 5,
                label = { Text("例如：项目名称、工作内容、今日心情") },
                supportingText = { Text("${text.length}/100") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun SizeDialog(scale: Float, onDismiss: () -> Unit, onScale: (Float) -> Unit, onReset: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("水印大小与位置") },
        text = {
            Column {
                Text("大小  ${(scale * 100).toInt()}%", color = Gold)
                Slider(value = scale, onValueChange = onScale, valueRange = .75f..1.4f)
                Text("在照片上拖动整块水印，即可调整位置。", color = Muted, fontSize = 12.sp)
                TextButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) { Text("恢复默认大小与左下角位置") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
