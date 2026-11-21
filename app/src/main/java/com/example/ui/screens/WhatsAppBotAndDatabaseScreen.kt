package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.AgroSightCloudWorker
import com.example.data.AuthLoginChannel
import com.example.data.DatabaseService
import com.example.data.WhatsAppDirection
import com.example.data.WhatsAppMessageEntity
import com.example.model.EGYPTIAN_ZONE_PRESETS
import com.example.model.SUPPORTED_CROPS
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.RoseSoft
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SkySoft
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate50
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.viewmodel.AgroSightUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhatsAppBotAndDatabaseScreen(
    uiState: AgroSightUiState,
    onSendDiseaseAlertToWhatsApp: () -> Unit,
    onSendMorningScheduleToWhatsApp: () -> Unit,
    onConfirmValveAdjustmentQuickAction: () -> Unit,
    onOpenHotspotMapQuickAction: () -> Unit,
    onRequestDailyReportQuickAction: () -> Unit,
    onSendFarmerChatQuestion: (String) -> Unit,
    onOpenAuthModal: () -> Unit,
    onOpenDatabaseSchemaModal: () -> Unit,
    onRunDailyCronDispatchAllFields: () -> Unit = {},
    onOpenDirectWaMeLink: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var chatInput by remember { mutableStateOf("") }
    var copiedWaLinkNotice by remember { mutableStateOf(false) }

    val farmerMessages = uiState.dbSnapshot.whatsappMessages.filter {
        it.farmerId == uiState.activeFarmer.id
    }
    val farmerLogs = uiState.dbSnapshot.irrigationAndTreatmentLogs.filter {
        it.fieldId == uiState.fieldId
    }
    val latestOutboundText = farmerMessages.lastOrNull {
        it.direction == WhatsAppDirection.OUTBOUND_ALERT
    }?.messageText ?: "تقرير الري اليومي لحقل ${uiState.fieldName} جاهز على منصة AgroSight."

    val activeWaMeUrl = uiState.lastDirectWaMeUrl.ifBlank {
        AgroSightCloudWorker.buildDirectWaMeLink(
            toPhone = uiState.activeFarmer.whatsappPhone,
            bodyText = latestOutboundText
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("whatsapp_bot_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Direct WhatsApp Links (https://wa.me/) & Database Message History Logger
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF075E54)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            Column {
                                Text(
                                    text = "إرسال واتساب المباشر (https://wa.me/) وسجل الرسائل",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Slate900,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "المزارع النشط: ${uiState.activeFarmer.fullName} (${uiState.activeFarmer.whatsappPhone})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmeraldDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "📧 ${uiState.activeFarmer.gmailAddress} • 🪪 الرقم القومي: ${uiState.activeFarmer.nationalId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate600
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                color = Slate100,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                                modifier = Modifier
                                    .clickable { onOpenAuthModal() }
                                    .testTag("open_whatsapp_auth_modal_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("دخول / الهوية", style = MaterialTheme.typography.labelMedium, color = Slate900)
                                }
                            }

                            Surface(
                                color = Slate900,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .clickable { onOpenDatabaseSchemaModal() }
                                    .testTag("open_db_schema_modal_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = CyanLight,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("قاعدة البيانات SQL", style = MaterialTheme.typography.labelMedium, color = Color.White)
                                }
                            }
                        }
                    }

                    // 2 Direct wa.me Broadcast Buttons (Logs to DB + Opens https://wa.me/ directly)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onSendDiseaseAlertToWhatsApp,
                            colors = ButtonDefaults.buttonColors(containerColor = RoseDanger),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("wa_broadcast_disease_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SatelliteAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "إرسال إنذار الأمراض عبر wa.me",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Button(
                            onClick = onSendMorningScheduleToWhatsApp,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075E54)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("wa_broadcast_morning_irrigation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "إرسال جدول الري الصباحي عبر wa.me",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Direct WhatsApp Link (https://wa.me/) Card & Instant Launcher
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(EmeraldSoft)
                            .border(1.dp, EmeraldPrimary, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🔗 رابط واتساب المباشر الجاهز للإرسال (Direct wa.me Link)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = EmeraldDark,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "يفتح تطبيق واتساب مباشرة مع رقم (${uiState.activeFarmer.whatsappPhone}) والرسالة مكتوبة وجاهزة للإرسال مع حفظها في سجل قاعدة البيانات (${farmerMessages.size} رسالة موثقة)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate700
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.dp, Slate200, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = activeWaMeUrl.take(145) + if (activeWaMeUrl.length > 145) "..." else "",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF075E54)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onOpenDirectWaMeLink(
                                        uiState.activeFarmer.whatsappPhone,
                                        latestOutboundText
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075E54)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag("open_direct_wa_me_now_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("فتح واتساب مباشرة (wa.me)", style = MaterialTheme.typography.labelMedium)
                            }

                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary),
                                modifier = Modifier
                                    .weight(0.9f)
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("AgroSight_WaMe_Link", activeWaMeUrl))
                                        copiedWaLinkNotice = true
                                    }
                                    .testTag("copy_direct_wa_me_link_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = EmeraldDark,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (copiedWaLinkNotice) "تم نسخ الرابط!" else "نسخ رابط wa.me",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EmeraldDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Multi-Tenant Cloud Worker Cron Job Trigger (POST /daily-dispatch generating wa.me links & logging in DB)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate900)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "محرك المسح الصباحي الآلي • agrosight-worker (Direct wa.me + DB Logger)",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "يحسب البخر-نتح من Open-Meteo ومؤشرات Sentinel-2 ويولّد رسائل وروابط https://wa.me/ جاهزة لكل مزارع مع توثيقها في جدول whatsapp_messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanLight
                            )
                        }

                        Button(
                            onClick = onRunDailyCronDispatchAllFields,
                            enabled = !uiState.isRunningDailyCronDispatch,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_daily_cron_dispatch_button")
                        ) {
                            if (uiState.isRunningDailyCronDispatch) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("جاري تشغيل المسح الصباحي وتوليد روابط wa.me لكل الحقول...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تشغيل المسح الصباحي الآلي وتوليد روابط wa.me لكل الحقول",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        if (uiState.cronProgressStatusAr.isNotBlank()) {
                            Text(
                                text = uiState.cronProgressStatusAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberLight,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        uiState.lastCronDispatchSummary?.let { batch ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Slate950)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "نتيجة الدفعة الصباحية (${batch.timestamp}) -> ${batch.statusJson}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = EmeraldLight,
                                    fontWeight = FontWeight.Bold
                                )
                                batch.stepResults.forEach { step ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Slate800)
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${step.farmerName} (${step.whatsappPhone}) • ${step.fieldName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "ET0=${formatDecimal2(step.et0Mm)}مم | Kc=${formatDecimal2(step.kcUsed)} | LR=${(step.leachingFraction * 100).toInt()}% | المقنن=${formatDecimal1(step.waterM3Feddan)} م³/فدان (${step.valveRunMinutes} دقيقة)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Slate200
                                            )
                                        }
                                        Surface(
                                            color = Color(0xFF25D366),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .clickable {
                                                    onOpenDirectWaMeLink(step.whatsappPhone, step.generatedMessageText)
                                                }
                                        ) {
                                            Text(
                                                text = "📲 إرسال wa.me",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Slate950,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Logged WhatsApp Message History & Direct wa.me Chat Window
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.5.dp, Color(0xFF075E54), RoundedCornerShape(20.dp))
                    .background(Color(0xFFECE5DD))
            ) {
                // WhatsApp Official Green Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF075E54))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFAF9F5))
                                .border(1.dp, EmeraldLight, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_agrosight_logo),
                                contentDescription = "شعار أجرو سايت الرسمي",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "سجل رسائل واتساب الموثق (AgroSight DB)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "حساب موثق",
                                    tint = Color(0xFF25D366),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = "روابط https://wa.me/${uiState.activeFarmer.whatsappPhone.filter { it.isDigit() }} • بدون كاميرات أو حساسات",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldSoft
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF128C7E),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "${farmerMessages.size} رسالة مسجلة",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Chat Messages Area (Logged in Database)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    farmerMessages.forEach { msg ->
                        WhatsAppMessageBubble(
                            message = msg,
                            farmerPhone = uiState.activeFarmer.whatsappPhone,
                            onOpenDirectWaMe = onOpenDirectWaMeLink
                        )
                    }

                    if (uiState.isSendingWhatsAppAiReply) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF075E54),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "جاري تحليل مؤشرات Sentinel-2 وتجهيز رابط wa.me عبر Gemini AI...",
                                style = MaterialTheme.typography.labelMedium,
                                color = Slate700
                            )
                        }
                    }
                }

                // Interactive WhatsApp Quick Action Buttons (أزرار الرد السريع داخل واتساب)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE2DDD5))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "أزرار التنفيذ التفاعلية الفورية داخل واتساب:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate700,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WhatsAppInteractiveReplyButton(
                            text = "✅ تأكيد تعديل الري للمحبس المصاب",
                            accentColor = EmeraldPrimary,
                            testTag = "wa_quick_confirm_valve",
                            onClick = onConfirmValveAdjustmentQuickAction
                        )
                        WhatsAppInteractiveReplyButton(
                            text = "📍 عرض خريطة البؤرة الحرارية",
                            accentColor = RoseDanger,
                            testTag = "wa_quick_open_hotspot_map",
                            onClick = onOpenHotspotMapQuickAction
                        )
                        WhatsAppInteractiveReplyButton(
                            text = "📊 طلب تقرير الري والتربة اليومي",
                            accentColor = SkyAccent,
                            testTag = "wa_quick_daily_report",
                            onClick = onRequestDailyReportQuickAction
                        )
                    }

                    // Suggested Egyptian Farmer Questions for Gemini AI
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "ليه الأوراق صفراء في المحبس 3؟",
                            "احسبلي جرعة حامض الفوسفوريك النهاردة",
                            "هل أرش مبيد فطري ولا العيب في الري؟"
                        ).forEachIndexed { idx, promptQ ->
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Slate400),
                                modifier = Modifier
                                    .clickable { onSendFarmerChatQuestion(promptQ) }
                                    .testTag("wa_suggested_question_$idx")
                            ) {
                                Text(
                                    text = "💬 $promptQ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF075E54),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // WhatsApp Text Input Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            placeholder = { Text("اكتب سؤالك بالعامية المصرية (مثال: أرش إيه للبؤرة؟)...") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.White, RoundedCornerShape(24.dp))
                                .testTag("whatsapp_chat_input")
                        )

                        IconButton(
                            onClick = {
                                if (chatInput.isNotBlank()) {
                                    onSendFarmerChatQuestion(chatInput)
                                    chatInput = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF075E54))
                                .testTag("whatsapp_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "إرسال",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Live Irrigation & Treatment Logs Table (`irrigationAndTreatmentLogs`)
        item {
            BentoCard(
                title = "سجل عمليات الري والمعالجة الوقائية المعتمد (Treatment Logs DB)",
                subtitle = "يتم تحديثه تلقائياً عند تأكيد تعديل المحابس أو إرسال توصيات الأحماض والرش البؤري",
                icon = Icons.Default.Storage,
                accentColor = EmeraldPrimary
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    farmerLogs.forEach { log ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Slate100)
                                .border(1.dp, Slate200, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${log.date} • ${log.preventiveSprayAction}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Slate900,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "ET0: ${formatDecimal2(log.et0)} مم | Kc: ${formatDecimal2(log.kc)} | المقنن: ${formatDecimal1(log.waterM3PerFeddan)} م³/فدان | الحامض: ${formatDecimal1(log.acidDoseLiters)} لتر/فدان",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate600
                                )
                            }
                            Surface(
                                color = EmeraldSoft,
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary)
                            ) {
                                Text(
                                    text = "${log.valveRunMinutes} دقيقة",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldDark,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppMessageBubble(
    message: WhatsAppMessageEntity,
    farmerPhone: String,
    onOpenDirectWaMe: (String, String) -> Unit
) {
    val isOutboundBot = message.direction == WhatsAppDirection.OUTBOUND_ALERT
    val bubbleColor = if (isOutboundBot) Color.White else Color(0xFFDCF8C6)
    val alignment = if (isOutboundBot) Alignment.CenterStart else Alignment.CenterEnd

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(14.dp))
                .background(bubbleColor)
                .border(
                    1.dp,
                    if (isOutboundBot) Slate200 else Color(0xFFB2E59E),
                    RoundedCornerShape(14.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isOutboundBot) Color(0xFF075E54).copy(alpha = 0.12f) else EmeraldSoft,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (isOutboundBot) "🛰️ ${message.alertCategory.badgeAr}" else "👨‍🌾 رد المزارع",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutboundBot) Color(0xFF075E54) else EmeraldDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${message.timestamp} • ${message.status}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500
                    )
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = null,
                        tint = SkyAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                text = message.messageText,
                style = MaterialTheme.typography.bodySmall,
                color = Slate900
            )

            // Direct https://wa.me/ launcher button for every logged message
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    color = Color(0xFF075E54).copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF075E54)),
                    modifier = Modifier
                        .clickable { onOpenDirectWaMe(farmerPhone, message.messageText) }
                        .testTag("wa_bubble_open_wa_me_${message.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = Color(0xFF075E54),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "📲 فتح وإرسال عبر واتساب المباشر (https://wa.me/${farmerPhone.filter { it.isDigit() }})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF075E54),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppInteractiveReplyButton(
    text: String,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, accentColor),
        modifier = Modifier
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FarmerWhatsAppAuthDialog(
    uiState: AgroSightUiState,
    onSwitchFarmer: (String) -> Unit,
    onAuthenticateSingleChannel: (AuthLoginChannel, String) -> Unit,
    onRegisterOrLogin: (String, String, String, String, String, String, Float) -> Unit,
    onDismiss: () -> Unit
) {
    // 0 = Fast Login by Mobile / Gmail / National ID, 1 = Full New Farmer Registration
    var authModeTab by remember { mutableStateOf(0) }
    var selectedChannel by remember { mutableStateOf(AuthLoginChannel.MOBILE_NUMBER) }
    var singleLoginInput by remember { mutableStateOf("+201012345678") }

    // Registration fields
    var fullName by remember { mutableStateOf("") }
    var whatsappPhone by remember { mutableStateOf("+2010") }
    var gmailAddress by remember { mutableStateOf("") }
    var nationalId by remember { mutableStateOf("28505141801492") }
    var selectedRegion by remember { mutableStateOf(EGYPTIAN_ZONE_PRESETS.first().nameAr) }
    var selectedCrop by remember { mutableStateOf(SUPPORTED_CROPS.first().nameAr) }
    var areaText by remember { mutableStateOf("50") }

    val nationalIdValidation = remember(nationalId) {
        DatabaseService.validateEgyptianNationalId(nationalId)
    }
    val singleNationalIdValidation = remember(singleLoginInput) {
        DatabaseService.validateEgyptianNationalId(singleLoginInput)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .testTag("farmer_whatsapp_auth_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with Official AgroSight Logo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFFAF9F5))
                                .border(1.5.dp, EmeraldPrimary, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_agrosight_logo),
                                contentDescription = "شعار أجرو سايت",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "بوابة تسجيل الدخول وتوثيق هوية المزارع",
                                style = MaterialTheme.typography.titleMedium,
                                color = Slate900,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "مصادقة موحدة عبر: رقم الموبايل • حساب Gmail • الرقم القومي المصري",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                // Error / Feedback Banner
                if (!uiState.authErrorMessageAr.isNullOrBlank()) {
                    Surface(
                        color = RoseSoft,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RoseDanger)
                    ) {
                        Text(
                            text = "⚠️ ${uiState.authErrorMessageAr}",
                            style = MaterialTheme.typography.labelMedium,
                            color = RoseDanger,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Mode Switcher: Fast Login vs New Account Registration
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Slate100)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = if (authModeTab == 0) Slate900 else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { authModeTab = 0 }
                            .testTag("auth_mode_login_tab")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = if (authModeTab == 0) EmeraldLight else Slate600,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "تسجيل دخول سريع",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (authModeTab == 0) Color.White else Slate700,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        color = if (authModeTab == 1) EmeraldPrimary else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { authModeTab = 1 }
                            .testTag("auth_mode_register_tab")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = if (authModeTab == 1) Color.White else Slate600,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "حساب مزارع جديد",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (authModeTab == 1) Color.White else Slate700,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (authModeTab == 0) {
                    // =========================================================================
                    // TAB 0: MULTI-CHANNEL FAST LOGIN (Mobile Number / Gmail / 14-digit National ID)
                    // =========================================================================
                    Text(
                        text = "اختر وسيلة التحقق لتسجيل الدخول الفوري:",
                        style = MaterialTheme.typography.labelLarge,
                        color = Slate900,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val channels = listOf(
                            Triple(AuthLoginChannel.MOBILE_NUMBER, "📱 الموبايل", Icons.Default.PhoneAndroid),
                            Triple(AuthLoginChannel.GMAIL_ACCOUNT, "📧 Gmail", Icons.Default.Email),
                            Triple(AuthLoginChannel.NATIONAL_ID, "🪪 الرقم القومي", Icons.Default.Badge)
                        )
                        channels.forEach { (ch, shortTitle, icon) ->
                            val isSelected = selectedChannel == ch
                            Surface(
                                color = if (isSelected) EmeraldSoft else Slate100,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    if (isSelected) EmeraldPrimary else Slate200
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedChannel = ch
                                        singleLoginInput = when (ch) {
                                            AuthLoginChannel.MOBILE_NUMBER -> uiState.activeFarmer.whatsappPhone
                                            AuthLoginChannel.GMAIL_ACCOUNT -> uiState.activeFarmer.gmailAddress
                                            AuthLoginChannel.NATIONAL_ID -> uiState.activeFarmer.nationalId
                                        }
                                    }
                                    .testTag("auth_channel_${ch.name.lowercase()}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = shortTitle,
                                        tint = if (isSelected) EmeraldDark else Slate600,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = shortTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) EmeraldDark else Slate700,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = singleLoginInput,
                        onValueChange = { newVal ->
                            singleLoginInput = if (selectedChannel == AuthLoginChannel.NATIONAL_ID) {
                                newVal.filter { it.isDigit() }.take(14)
                            } else {
                                newVal
                            }
                        },
                        label = { Text(selectedChannel.labelAr) },
                        placeholder = { Text(selectedChannel.hintAr) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (selectedChannel) {
                                AuthLoginChannel.MOBILE_NUMBER -> KeyboardType.Phone
                                AuthLoginChannel.GMAIL_ACCOUNT -> KeyboardType.Email
                                AuthLoginChannel.NATIONAL_ID -> KeyboardType.Number
                            }
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_single_channel_input")
                    )

                    // Channel-specific smart helper / validation badge
                    when (selectedChannel) {
                        AuthLoginChannel.NATIONAL_ID -> {
                            Surface(
                                color = if (singleNationalIdValidation.isValid) EmeraldSoft else AmberSoft,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (singleNationalIdValidation.isValid) EmeraldPrimary else AmberWarning
                                )
                            ) {
                                Text(
                                    text = "🪪 فحص السجل المدني: ${singleNationalIdValidation.statusMessageAr}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (singleNationalIdValidation.isValid) EmeraldDark else Slate800,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        AuthLoginChannel.GMAIL_ACCOUNT -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "mahmoud.amalfarm@gmail.com",
                                    "tarek.salhia.agro@gmail.com",
                                    "abdelsalam.farafra@gmail.com"
                                ).forEachIndexed { idx, emailPreset ->
                                    Surface(
                                        color = SkySoft,
                                        shape = RoundedCornerShape(50),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, SkyAccent),
                                        modifier = Modifier
                                            .clickable { singleLoginInput = emailPreset }
                                            .testTag("gmail_quick_preset_$idx")
                                    ) {
                                        Text(
                                            text = "📧 $emailPreset",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Slate900,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        AuthLoginChannel.MOBILE_NUMBER -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "+201012345678",
                                    "+201123456789",
                                    "+201234567890"
                                ).forEachIndexed { idx, phonePreset ->
                                    Surface(
                                        color = EmeraldSoft,
                                        shape = RoundedCornerShape(50),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary),
                                        modifier = Modifier
                                            .clickable { singleLoginInput = phonePreset }
                                            .testTag("mobile_quick_preset_$idx")
                                    ) {
                                        Text(
                                            text = "📱 $phonePreset",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmeraldDark,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            onAuthenticateSingleChannel(selectedChannel, singleLoginInput)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_fast_login_submit_btn")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تحقق وتسجيل الدخول عبر ${selectedChannel.labelAr}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // =========================================================================
                    // TAB 1: NEW FARMER REGISTRATION (Mobile + Gmail + 14-digit National ID)
                    // =========================================================================
                    Text(
                        text = "تسجيل وتوثيق حساب مزارع جديد (موبايل + Gmail + الرقم القومي):",
                        style = MaterialTheme.typography.labelLarge,
                        color = Slate900,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("اسم المزارع / المهندس الثلاثي") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_farmer_name_input")
                    )

                    OutlinedTextField(
                        value = whatsappPhone,
                        onValueChange = { whatsappPhone = it },
                        label = { Text("رقم الموبايل / واتساب (+201xxxxxxxxx)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_whatsapp_phone_input")
                    )

                    OutlinedTextField(
                        value = gmailAddress,
                        onValueChange = { gmailAddress = it },
                        label = { Text("البريد الإلكتروني (Gmail Account)") },
                        placeholder = { Text("example@gmail.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_gmail_input")
                    )

                    if (!gmailAddress.contains("@") && gmailAddress.isNotBlank()) {
                        Surface(
                            color = SkySoft,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable {
                                gmailAddress = "${gmailAddress.trim()}@gmail.com"
                            }
                        ) {
                            Text(
                                text = "➕ إكمال تلقائي: ${gmailAddress.trim()}@gmail.com",
                                style = MaterialTheme.typography.labelSmall,
                                color = SkyAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = nationalId,
                        onValueChange = { input ->
                            nationalId = input.filter { it.isDigit() }.take(14)
                        },
                        label = { Text("الرقم القومي المصري (14 رقماً - ${nationalId.length}/14)") },
                        placeholder = { Text("28505141801492") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_national_id_input")
                    )

                    Surface(
                        color = if (nationalIdValidation.isValid) EmeraldSoft else AmberSoft,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (nationalIdValidation.isValid) EmeraldPrimary else AmberWarning
                        )
                    ) {
                        Text(
                            text = "🪪 ${nationalIdValidation.statusMessageAr}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (nationalIdValidation.isValid) EmeraldDark else Slate800,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = selectedRegion,
                            onValueChange = { selectedRegion = it },
                            label = { Text("المنطقة الزراعية") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = areaText,
                            onValueChange = { areaText = it },
                            label = { Text("المساحة (فدان)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.8f)
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SUPPORTED_CROPS.take(5).forEach { crop ->
                            val sel = selectedCrop == crop.nameAr
                            Surface(
                                color = if (sel) EmeraldPrimary else Slate100,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { selectedCrop = crop.nameAr }
                            ) {
                                Text(
                                    text = crop.nameAr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) Color.White else Slate800,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val area = areaText.toFloatOrNull() ?: 50f
                            onRegisterOrLogin(
                                fullName,
                                whatsappPhone,
                                gmailAddress,
                                nationalId,
                                selectedRegion,
                                selectedCrop,
                                area
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_submit_button")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "توثيق الهوية وتفعيل الحساب (موبايل + Gmail + الرقم القومي)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = Slate200)

                // Registered Egyptian Farmers List with Full Credentials (Mobile, Gmail, National ID)
                Text(
                    text = "الحسابات المصرية الموثقة (تبديل فوري):",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800,
                    fontWeight = FontWeight.Bold
                )

                uiState.dbSnapshot.farmers.forEach { farmer ->
                    val isCurrent = farmer.id == uiState.activeFarmer.id
                    val natDecode = DatabaseService.validateEgyptianNationalId(farmer.nationalId)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isCurrent) EmeraldSoft else Slate100)
                            .border(
                                1.5.dp,
                                if (isCurrent) EmeraldPrimary else Slate200,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                onSwitchFarmer(farmer.id)
                                onDismiss()
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = farmer.fullName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Slate900,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = if (isCurrent) EmeraldPrimary else Slate200,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = farmer.region,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isCurrent) Color.White else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "📱 الموبايل: ${farmer.whatsappPhone} • 📧 ${farmer.gmailAddress}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate700,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "🪪 الرقم القومي: ${farmer.nationalId} (${natDecode.governorateAr} • ${natDecode.birthYearSummaryAr})",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldDark
                            )
                        }
                        if (isCurrent) {
                            Surface(
                                color = EmeraldPrimary,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "نشط ✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
fun RelationalDatabaseSchemaDialog(
    uiState: AgroSightUiState,
    sqlSchemaText: String,
    edgeFunctionCodeText: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copiedNotice by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) } // 0 = SQL Schema, 1 = Edge Function Worker

    val displayedCode = if (activeTab == 0) sqlSchemaText else edgeFunctionCodeText

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .testTag("database_schema_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate900)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "قاعدة البيانات العلائقية ومحرك السحابة (Supabase + Worker)",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "6 جداول مترابطة + دالة المسح الصباحي الآلي و Meta Webhook",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldLight
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("AgroSight_Code", displayedCode))
                                copiedNotice = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (copiedNotice) "تم النسخ!" else "نسخ الكود")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Live Table Counts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DbCountChip(Modifier.weight(1f), "farmers", uiState.dbSnapshot.farmers.size)
                        DbCountChip(Modifier.weight(1f), "fields", uiState.dbSnapshot.fields.size)
                        DbCountChip(Modifier.weight(1f), "scans", uiState.dbSnapshot.satelliteSpectralScans.size)
                        DbCountChip(Modifier.weight(1f), "whatsapp", uiState.dbSnapshot.whatsappMessages.size)
                    }

                    // Tab Switcher: SQL DDL vs Deno Edge Function
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = if (activeTab == 0) EmeraldPrimary else Slate100,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    activeTab = 0
                                    copiedNotice = false
                                }
                                .testTag("db_tab_sql_schema")
                        ) {
                            Text(
                                text = "مخطط الجداول (PostgreSQL DDL)",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (activeTab == 0) Color.White else Slate800,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Surface(
                            color = if (activeTab == 1) EmeraldPrimary else Slate100,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    activeTab = 1
                                    copiedNotice = false
                                }
                                .testTag("db_tab_edge_function")
                        ) {
                            Text(
                                text = "محرك agrosight-worker/index.ts",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (activeTab == 1) Color.White else Slate800,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate950)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = displayedCode,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = EmeraldLight
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DbCountChip(modifier: Modifier = Modifier, table: String, count: Int) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Slate100)
            .border(1.dp, Slate200, RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(table, style = MaterialTheme.typography.labelSmall, color = Slate600)
        Text("$count سجل", style = MaterialTheme.typography.titleSmall, color = EmeraldDark, fontWeight = FontWeight.Bold)
    }
}
