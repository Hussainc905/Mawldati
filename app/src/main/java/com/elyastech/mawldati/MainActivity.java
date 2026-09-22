package com.elyastech.mawldati;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String SUPABASE_URL = "https://admwtddiylyofpwtauui.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_0CY42ztOEqpVSUEDBtJV5w_a_8W6Zg7";
    private static final String APP_VERSION = "0.2.0";
    private static final String SUPPORT_PHONE = "07722523232";

    private static final String PREFS = "mawldati_license";
    private static final String P_CODE = "activation_code";
    private static final String P_STATUS = "license_status";
    private static final String P_EXPIRES = "expires_at";
    private static final String P_ACTIVATED = "activated_at";
    private static final String P_OWNER = "owner_name";
    private static final String P_GENERATOR = "generator_name";
    private static final String P_GRACE = "offline_grace_days";
    private static final String P_LAST_OK = "last_ok_epoch";

    private SharedPreferences prefs;
    private ExecutorService executor;
    private String deviceId;
    private EditText activationCode;
    private Button activationButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 251));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(0xFF0B1830);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        executor = Executors.newSingleThreadExecutor();
        deviceId = buildDeviceId();

        String savedCode = prefs.getString(P_CODE, "");
        if (savedCode == null || savedCode.trim().isEmpty()) {
            renderActivation("التطبيق غير مفعّل", "أدخل رمز التفعيل الصادر من لوحة الإدارة.", 0xFF64748B);
        } else {
            renderLoading("جارِ التحقق من الاشتراك", "يتم الاتصال بالسيرفر والتحقق من الترخيص المحفوظ...");
            checkLicense(savedCode.trim().toUpperCase(Locale.ROOT));
        }
    }

    private void checkLicense(String code) {
        executor.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("p_activation_code", code);
                request.put("p_device_id", deviceId);
                request.put("p_app_version", APP_VERSION);

                URL url = new URL(SUPABASE_URL + "/rest/v1/rpc/check_license");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setDoOutput(true);
                c.setRequestProperty("apikey", SUPABASE_KEY);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "application/json");

                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }

                int http = c.getResponseCode();
                InputStream stream = http >= 200 && http < 300 ? c.getInputStream() : c.getErrorStream();
                String response = readAll(stream);
                if (http < 200 || http >= 300) {
                    throw new Exception("HTTP " + http + ": " + response);
                }

                JSONObject json = new JSONObject(response);
                boolean ok = json.optBoolean("ok", false);
                String status = json.optString("status", "unknown");
                String expiresAt = json.optString("expires_at", "");
                String activatedAt = json.optString("activated_at", "");
                String ownerName = json.optString("owner_name", "");
                String generatorName = json.optString("generator_name", "");
                int graceDays = json.optInt("offline_grace_days", 3);

                SharedPreferences.Editor ed = prefs.edit()
                        .putString(P_CODE, code)
                        .putString(P_STATUS, status)
                        .putString(P_EXPIRES, expiresAt)
                        .putInt(P_GRACE, graceDays);

                if (!activatedAt.isEmpty()) ed.putString(P_ACTIVATED, activatedAt);
                if (!ownerName.isEmpty()) ed.putString(P_OWNER, ownerName);
                if (!generatorName.isEmpty()) ed.putString(P_GENERATOR, generatorName);

                if (ok) {
                    ed.putLong(P_LAST_OK, System.currentTimeMillis());
                }
                ed.apply();

                runOnUiThread(() -> handleServerState(ok, status, expiresAt, graceDays));
            } catch (Exception e) {
                runOnUiThread(() -> handleNetworkFailure(e));
            }
        });
    }

    private void handleServerState(boolean ok, String status, String expiresAt, int graceDays) {
        if (ok && ("trial".equals(status) || "active".equals(status))) {
            renderHome(status, false, graceDays);
            return;
        }

        String title;
        String details;
        int color = 0xFFDC2626;

        switch (status) {
            case "expired":
                title = "الاشتراك منتهي";
                details = "انتهى الاشتراك في " + formatDate(expiresAt) + ". جدد الاشتراك من الإدارة ثم اضغط فحص الاشتراك.";
                break;
            case "suspended":
                title = "الترخيص موقوف";
                details = "تم إيقاف الترخيص من لوحة الإدارة. تواصل مع الدعم الفني إذا كان الإيقاف غير مقصود.";
                break;
            case "device_mismatch":
                title = "الجهاز غير مسموح";
                details = "رمز التفعيل مربوط بجهاز آخر. يجب فك ربط الجهاز من لوحة الإدارة أولًا.";
                break;
            case "not_found":
                title = "رمز التفعيل غير موجود";
                details = "تأكد من الرمز ثم حاول مرة أخرى.";
                break;
            case "invalid_code":
                title = "رمز غير صحيح";
                details = "أدخل رمز التفعيل كاملًا.";
                break;
            case "invalid_device":
                title = "تعذر التعرف على الجهاز";
                details = "أعد تشغيل التطبيق وحاول مرة أخرى.";
                break;
            default:
                title = "تعذر التفعيل";
                details = "استجابة غير متوقعة من السيرفر: " + status;
                break;
        }
        renderActivation(title, details, color);
    }

    private void handleNetworkFailure(Exception error) {
        String savedStatus = prefs.getString(P_STATUS, "");
        String expires = prefs.getString(P_EXPIRES, "");
        int graceDays = prefs.getInt(P_GRACE, 3);
        long lastOk = prefs.getLong(P_LAST_OK, 0L);
        long graceMs = graceDays * 24L * 60L * 60L * 1000L;
        boolean insideGrace = lastOk > 0 && System.currentTimeMillis() - lastOk <= graceMs;
        boolean notExpired = isNotExpired(expires);
        boolean wasAllowed = "active".equals(savedStatus) || "trial".equals(savedStatus);

        if (insideGrace && notExpired && wasAllowed) {
            renderHome(savedStatus, true, graceDays);
        } else {
            renderActivation("لا يوجد اتصال بالسيرفر",
                    "تعذر الوصول إلى السيرفر ولا توجد مهلة دون إنترنت صالحة.\n" + safeError(error),
                    0xFFDC2626);
        }
    }

    private void renderLoading(String title, String details) {
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addHeader(root, false);

        LinearLayout card = card();
        TextView t = text(title, 22, 0xFF0B1830, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t, matchWrap());

        TextView d = text(details, 15, 0xFF64748B, false);
        d.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dLp = matchWrap();
        dLp.setMargins(0, dp(10), 0, 0);
        card.addView(d, dLp);
        root.addView(card, matchWrap());

        TextView wait = text("●  جارِ الاتصال", 16, 0xFF2563EB, true);
        wait.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wLp = matchWrap();
        wLp.setMargins(0, dp(18), 0, 0);
        root.addView(wait, wLp);
        addFooter(root);
        setContentView(scroll);
    }

    private void renderActivation(String stateTitle, String stateDetails, int stateColor) {
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addHeader(root, false);

        LinearLayout card = card();
        TextView codeLabel = text("رمز التفعيل", 16, 0xFF334155, true);
        card.addView(codeLabel, matchWrap());

        activationCode = new EditText(this);
        activationCode.setHint("مثال: GEN-A2HQ-4PUS");
        activationCode.setSingleLine(true);
        activationCode.setTextSize(18);
        activationCode.setGravity(Gravity.CENTER);
        activationCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        activationCode.setPadding(dp(12), dp(13), dp(12), dp(13));
        activationCode.setBackground(makeRounded(0xFFF8FAFC, 12));
        String saved = prefs.getString(P_CODE, "");
        if (saved != null) activationCode.setText(saved);
        LinearLayout.LayoutParams editLp = matchWrap();
        editLp.setMargins(0, dp(9), 0, dp(14));
        card.addView(activationCode, editLp);

        activationButton = primaryButton("تفعيل / فحص الاشتراك");
        activationButton.setOnClickListener(v -> {
            String code = activationCode.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (code.length() < 6) {
                Toast.makeText(this, "أدخل رمز التفعيل كاملًا", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(P_CODE, code).apply();
            renderLoading("جارِ فحص الاشتراك", "يتم التحقق من الرمز وربطه بهذا الجهاز...");
            checkLicense(code);
        });
        card.addView(activationButton, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(card, matchWrap());

        TextView state = text("●  " + stateTitle, 20, stateColor, true);
        state.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = matchWrap();
        sLp.setMargins(0, dp(22), 0, dp(5));
        root.addView(state, sLp);

        TextView details = text(stateDetails, 15, 0xFF64748B, false);
        details.setGravity(Gravity.CENTER);
        root.addView(details, matchWrap());

        TextView dev = text("معرّف الجهاز: " + shortDeviceId(), 12, 0xFF94A3B8, false);
        dev.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams devLp = matchWrap();
        devLp.setMargins(0, dp(12), 0, dp(18));
        root.addView(dev, devLp);

        Button support = outlineButton("الدعم الفني  " + SUPPORT_PHONE);
        support.setOnClickListener(v -> callSupport());
        root.addView(support, new LinearLayout.LayoutParams(-1, dp(48)));

        addFooter(root);
        setContentView(scroll);
    }

    private void renderHome(String status, boolean offline, int graceDays) {
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addHeader(root, true);

        String owner = prefs.getString(P_OWNER, "");
        String generator = prefs.getString(P_GENERATOR, "");
        String activatedAt = prefs.getString(P_ACTIVATED, "");
        String expiresAt = prefs.getString(P_EXPIRES, "");
        long daysLeft = remainingDays(expiresAt);

        TextView welcome = text(owner == null || owner.isEmpty() ? "مرحباً بك" : "مرحباً، " + owner, 24, 0xFF0B1830, true);
        root.addView(welcome, matchWrap());

        TextView generatorText = text(generator == null || generator.isEmpty() ? "مولدتي" : "المولدة: " + generator, 15, 0xFF64748B, false);
        LinearLayout.LayoutParams genLp = matchWrap();
        genLp.setMargins(0, dp(3), 0, dp(16));
        root.addView(generatorText, genLp);

        int statusColor = "trial".equals(status) ? 0xFFF59E0B : 0xFF16A34A;
        String statusLabel = "trial".equals(status) ? "تجريبي" : "فعال";
        if (offline) {
            statusColor = 0xFFF59E0B;
            statusLabel += " • دون إنترنت";
        }

        LinearLayout statusCard = card();
        TextView statusTitle = text("●  " + statusLabel, 21, statusColor, true);
        statusCard.addView(statusTitle, matchWrap());

        TextView statusSub = text(
                offline ? "تم السماح مؤقتًا ضمن مهلة العمل دون إنترنت: " + graceDays + " أيام." : "تم التحقق من الاشتراك من السيرفر بنجاح.",
                14, 0xFF64748B, false);
        LinearLayout.LayoutParams ssLp = matchWrap();
        ssLp.setMargins(0, dp(7), 0, 0);
        statusCard.addView(statusSub, ssLp);
        root.addView(statusCard, matchWrap());

        LinearLayout subCard = card();
        LinearLayout.LayoutParams subCardLp = matchWrap();
        subCardLp.setMargins(0, dp(14), 0, 0);
        root.addView(subCard, subCardLp);
        subCard.addView(text("تفاصيل الاشتراك", 19, 0xFF0B1830, true), matchWrap());
        addInfoRow(subCard, "من تاريخ", activatedAt == null || activatedAt.isEmpty() ? "سيظهر بعد تحديث دالة السيرفر" : formatDate(activatedAt));
        addInfoRow(subCard, "إلى تاريخ", formatDate(expiresAt));
        addInfoRow(subCard, "الأيام المتبقية", daysLeft < 0 ? "غير محدد" : daysLeft + " يوم");
        addInfoRow(subCard, "حالة الاتصال", offline ? "دون إنترنت - سماح مؤقت" : "السيرفر متصل");

        LinearLayout actions = card();
        LinearLayout.LayoutParams actionsLp = matchWrap();
        actionsLp.setMargins(0, dp(14), 0, 0);
        root.addView(actions, actionsLp);
        actions.addView(text("الخدمات", 19, 0xFF0B1830, true), matchWrap());

        Button refresh = primaryButton("فحص الاشتراك الآن");
        refresh.setOnClickListener(v -> {
            String code = prefs.getString(P_CODE, "");
            if (code == null || code.isEmpty()) {
                renderActivation("التطبيق غير مفعّل", "أدخل رمز التفعيل.", 0xFF64748B);
                return;
            }
            renderLoading("جارِ تحديث الاشتراك", "يتم الاتصال بالسيرفر للحصول على أحدث حالة...");
            checkLicense(code);
        });
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(-1, dp(50));
        refLp.setMargins(0, dp(12), 0, dp(9));
        actions.addView(refresh, refLp);

        Button support = outlineButton("الاتصال بالدعم الفني");
        support.setOnClickListener(v -> callSupport());
        LinearLayout.LayoutParams supportLp = new LinearLayout.LayoutParams(-1, dp(48));
        supportLp.setMargins(0, 0, 0, dp(9));
        actions.addView(support, supportLp);

        Button copyDevice = outlineButton("نسخ معرّف الجهاز");
        copyDevice.setOnClickListener(v -> copyDeviceId());
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-1, dp(48));
        copyLp.setMargins(0, 0, 0, dp(9));
        actions.addView(copyDevice, copyLp);

        Button changeCode = outlineButton("تغيير رمز التفعيل");
        changeCode.setTextColor(0xFFB91C1C);
        changeCode.setOnClickListener(v -> confirmChangeCode());
        actions.addView(changeCode, new LinearLayout.LayoutParams(-1, dp(48)));

        addFooter(root);
        setContentView(scroll);
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout.LayoutParams rowLp = matchWrap();
        rowLp.setMargins(0, dp(12), 0, 0);
        parent.addView(row, rowLp);

        TextView l = text(label, 14, 0xFF64748B, false);
        TextView v = text(value, 15, 0xFF1E293B, true);
        v.setGravity(Gravity.LEFT);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private ScrollView newBaseScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF4F7FB);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));
        return scroll;
    }

    private void addHeader(LinearLayout root, boolean compact) {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.elyas_tech_logo);
        logo.setAdjustViewBounds(true);
        int h = compact ? 78 : 105;
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(-1, dp(h));
        logoLp.setMargins(0, 0, 0, dp(8));
        root.addView(logo, logoLp);

        TextView title = text("مولدتي", compact ? 25 : 30, 0xFF0B1830, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text("ELYAS-TECH • إدارة وترخيص التطبيق", 14, 0xFF64748B, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.setMargins(0, dp(3), 0, dp(compact ? 16 : 22));
        root.addView(subtitle, subLp);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(makeRounded(0xFFFFFFFF, 18));
        return card;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(makeRounded(0xFF0A8F83, 12));
        return b;
    }

    private Button outlineButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(0xFF0F766E);
        b.setAllCaps(false);
        android.graphics.drawable.GradientDrawable d = makeRounded(0xFFFFFFFF, 12);
        d.setStroke(dp(1), 0xFFCBD5E1);
        b.setBackground(d);
        return b;
    }

    private void addFooter(LinearLayout root) {
        TextView version = text("الإصدار " + APP_VERSION + "  •  " + SUPPORT_PHONE, 12, 0xFF94A3B8, false);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vLp = matchWrap();
        vLp.setMargins(0, dp(28), 0, dp(8));
        root.addView(version, vLp);
    }

    private void callSupport() {
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + SUPPORT_PHONE));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, SUPPORT_PHONE, Toast.LENGTH_LONG).show();
        }
    }

    private void copyDeviceId() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Mawldati Device ID", deviceId));
            Toast.makeText(this, "تم نسخ معرّف الجهاز", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmChangeCode() {
        new AlertDialog.Builder(this)
                .setTitle("تغيير رمز التفعيل")
                .setMessage("سيتم حذف رمز التفعيل المحفوظ من هذا الهاتف فقط. هل تريد المتابعة؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة", (dialog, which) -> {
                    prefs.edit()
                            .remove(P_CODE)
                            .remove(P_STATUS)
                            .remove(P_EXPIRES)
                            .remove(P_ACTIVATED)
                            .remove(P_OWNER)
                            .remove(P_GENERATOR)
                            .remove(P_LAST_OK)
                            .apply();
                    renderActivation("التطبيق غير مفعّل", "أدخل رمز التفعيل الجديد.", 0xFF64748B);
                })
                .show();
    }

    private String buildDeviceId() {
        String raw = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (raw == null || raw.trim().isEmpty()) raw = "unknown-android-device";
        return "android-" + sha256(raw + "|com.elyastech.mawldati");
    }

    private String shortDeviceId() {
        return deviceId.length() > 20 ? deviceId.substring(0, 20) + "…" : deviceId;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String safeError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() > 120) return "تحقق من اتصال الإنترنت.";
        return m;
    }

    private static boolean isNotExpired(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return true;
        try {
            return Instant.parse(iso).isAfter(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return "غير محدد";
        try {
            Instant i = Instant.parse(iso);
            return DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
                    .withZone(ZoneId.systemDefault())
                    .format(i);
        } catch (Exception e) {
            return iso.length() >= 10 ? iso.substring(0, 10).replace('-', '/') : iso;
        }
    }

    private static long remainingDays(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return -1;
        try {
            LocalDate today = LocalDate.now();
            LocalDate end = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate();
            return Math.max(0, ChronoUnit.DAYS.between(today, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        t.setLineSpacing(0, 1.12f);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private android.graphics.drawable.GradientDrawable makeRounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
