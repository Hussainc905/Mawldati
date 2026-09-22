package com.elyastech.mawldati;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String SUPABASE_URL = "https://admwtddiylyofpwtauui.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_0CY42ztOEqpVSUEDBtJV5w_a_8W6Zg7";
    private static final String APP_VERSION = "0.1.0";

    private static final String PREFS = "mawldati_license";
    private static final String P_CODE = "activation_code";
    private static final String P_STATUS = "license_status";
    private static final String P_EXPIRES = "expires_at";
    private static final String P_GRACE = "offline_grace_days";
    private static final String P_LAST_OK = "last_ok_epoch";

    private EditText activationCode;
    private TextView statusTitle;
    private TextView statusDetails;
    private TextView deviceText;
    private TextView contentTitle;
    private Button checkButton;
    private LinearLayout appContent;
    private SharedPreferences prefs;
    private ExecutorService executor;
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 251));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        executor = Executors.newSingleThreadExecutor();
        deviceId = buildDeviceId();
        buildUi();

        String saved = prefs.getString(P_CODE, "");
        activationCode.setText(saved);
        if (!saved.isEmpty()) {
            checkLicense(saved, true);
        } else {
            showState("غير مفعّل", "أدخل رمز التفعيل الذي يصدر من لوحة الإدارة.", 0xFF6B7280, false);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF4F7FB);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(30));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.elyastech.mawldati.R.drawable.elyas_tech_logo);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(-1, dp(105));
        logoLp.setMargins(0, 0, 0, dp(8));
        root.addView(logo, logoLp);

        TextView title = text("مولدتي", 30, 0xFF0B1830, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text("ELYAS-TECH • إدارة وترخيص التطبيق", 14, 0xFF64748B, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.setMargins(0, dp(4), 0, dp(22));
        root.addView(subtitle, subLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(makeRounded(0xFFFFFFFF, 18));
        root.addView(card, matchWrap());

        TextView codeLabel = text("رمز التفعيل", 15, 0xFF334155, true);
        card.addView(codeLabel, matchWrap());

        activationCode = new EditText(this);
        activationCode.setHint("مثال: ELYAS-123456");
        activationCode.setSingleLine(true);
        activationCode.setTextSize(18);
        activationCode.setGravity(Gravity.CENTER);
        activationCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        activationCode.setPadding(dp(12), dp(12), dp(12), dp(12));
        activationCode.setBackground(makeRounded(0xFFF8FAFC, 12));
        LinearLayout.LayoutParams editLp = matchWrap();
        editLp.setMargins(0, dp(8), 0, dp(14));
        card.addView(activationCode, editLp);

        checkButton = new Button(this);
        checkButton.setText("تفعيل / فحص الاشتراك");
        checkButton.setTextSize(16);
        checkButton.setTextColor(Color.WHITE);
        checkButton.setAllCaps(false);
        checkButton.setBackground(makeRounded(0xFF0A8F83, 12));
        checkButton.setOnClickListener(v -> {
            String code = activationCode.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (code.length() < 6) {
                showState("رمز غير صحيح", "أدخل رمز التفعيل كاملًا.", 0xFFDC2626, false);
                return;
            }
            checkLicense(code, false);
        });
        card.addView(checkButton, new LinearLayout.LayoutParams(-1, dp(52)));

        statusTitle = text("", 20, 0xFF0B1830, true);
        statusTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = matchWrap();
        stLp.setMargins(0, dp(22), 0, dp(5));
        root.addView(statusTitle, stLp);

        statusDetails = text("", 15, 0xFF64748B, false);
        statusDetails.setGravity(Gravity.CENTER);
        root.addView(statusDetails, matchWrap());

        deviceText = text("معرّف الجهاز: " + shortDeviceId(), 12, 0xFF94A3B8, false);
        deviceText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams devLp = matchWrap();
        devLp.setMargins(0, dp(12), 0, dp(18));
        root.addView(deviceText, devLp);

        appContent = new LinearLayout(this);
        appContent.setOrientation(LinearLayout.VERTICAL);
        appContent.setPadding(dp(18), dp(18), dp(18), dp(18));
        appContent.setBackground(makeRounded(0xFFFFFFFF, 18));
        appContent.setVisibility(View.GONE);
        root.addView(appContent, matchWrap());

        contentTitle = text("تم فتح التطبيق", 22, 0xFF0B1830, true);
        contentTitle.setGravity(Gravity.CENTER);
        appContent.addView(contentTitle, matchWrap());

        TextView content = text("هذه شاشة الاختبار الأولى. ظهورها يعني أن التحقق من الترخيص نجح وأن التطبيق سمح بالدخول.", 16, 0xFF475569, false);
        content.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams contentLp = matchWrap();
        contentLp.setMargins(0, dp(10), 0, 0);
        appContent.addView(content, contentLp);

        TextView version = text("الإصدار " + APP_VERSION + "  •  07722523232", 12, 0xFF94A3B8, false);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vLp = matchWrap();
        vLp.setMargins(0, dp(28), 0, 0);
        root.addView(version, vLp);

        setContentView(scroll);
    }

    private void checkLicense(String code, boolean auto) {
        checkButton.setEnabled(false);
        checkButton.setText("جارِ الاتصال بالسيرفر...");
        showState("جارِ الفحص", auto ? "يتم التحقق من الاشتراك المحفوظ..." : "يتم التحقق من رمز التفعيل...", 0xFF2563EB, false);

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
                try (OutputStream os = c.getOutputStream()) { os.write(body); }

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
                int graceDays = json.optInt("offline_grace_days", 3);

                if (ok) {
                    prefs.edit()
                            .putString(P_CODE, code)
                            .putString(P_STATUS, status)
                            .putString(P_EXPIRES, expiresAt)
                            .putInt(P_GRACE, graceDays)
                            .putLong(P_LAST_OK, System.currentTimeMillis())
                            .apply();
                }

                runOnUiThread(() -> handleServerState(ok, status, expiresAt, graceDays));
            } catch (Exception e) {
                runOnUiThread(() -> handleNetworkFailure(e));
            }
        });
    }

    private void handleServerState(boolean ok, String status, String expiresAt, int graceDays) {
        resetButton();
        String until = formatExpiry(expiresAt);
        if (ok && "trial".equals(status)) {
            showState("تجريبي", "الاشتراك التجريبي صالح إلى: " + until, 0xFFF59E0B, true);
        } else if (ok && "active".equals(status)) {
            showState("فعال", "الاشتراك فعال إلى: " + until, 0xFF16A34A, true);
        } else if ("expired".equals(status)) {
            showState("منتهي", "انتهى الاشتراك في: " + until, 0xFFDC2626, false);
        } else if ("suspended".equals(status)) {
            showState("موقوف", "تم إيقاف الترخيص من لوحة الإدارة.", 0xFFDC2626, false);
        } else if ("device_mismatch".equals(status)) {
            showState("جهاز غير مسموح", "رمز التفعيل مربوط بجهاز آخر. يجب فك ربط الجهاز من لوحة الإدارة.", 0xFFDC2626, false);
        } else if ("not_found".equals(status)) {
            showState("الرمز غير موجود", "تأكد من رمز التفعيل ثم حاول مرة أخرى.", 0xFFDC2626, false);
        } else if ("invalid_code".equals(status)) {
            showState("رمز غير صحيح", "أدخل رمز التفعيل كاملًا.", 0xFFDC2626, false);
        } else {
            showState("تعذر التفعيل", "استجابة غير متوقعة من السيرفر: " + status, 0xFFDC2626, false);
        }
    }

    private void handleNetworkFailure(Exception error) {
        resetButton();
        String savedStatus = prefs.getString(P_STATUS, "");
        String expires = prefs.getString(P_EXPIRES, "");
        int graceDays = prefs.getInt(P_GRACE, 3);
        long lastOk = prefs.getLong(P_LAST_OK, 0);
        long graceMs = graceDays * 24L * 60L * 60L * 1000L;
        boolean insideGrace = lastOk > 0 && System.currentTimeMillis() - lastOk <= graceMs;
        boolean notExpired = isNotExpired(expires);
        boolean wasAllowed = "active".equals(savedStatus) || "trial".equals(savedStatus);

        if (insideGrace && notExpired && wasAllowed) {
            String label = "trial".equals(savedStatus) ? "تجريبي" : "فعال";
            showState(label + " • دون إنترنت", "تعذر الاتصال بالسيرفر. تم السماح مؤقتًا ضمن مهلة " + graceDays + " أيام.", 0xFFF59E0B, true);
        } else {
            showState("لا يوجد اتصال", "تعذر الوصول إلى السيرفر ولا توجد مهلة دون إنترنت صالحة.\n" + safeError(error), 0xFFDC2626, false);
        }
    }

    private void showState(String title, String details, int color, boolean allow) {
        statusTitle.setText("●  " + title);
        statusTitle.setTextColor(color);
        statusDetails.setText(details);
        appContent.setVisibility(allow ? View.VISIBLE : View.GONE);
    }

    private void resetButton() {
        checkButton.setEnabled(true);
        checkButton.setText("تفعيل / فحص الاشتراك");
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
        if (iso == null || iso.isEmpty()) return true;
        try { return Instant.parse(iso).isAfter(Instant.now()); }
        catch (Exception e) { return true; }
    }

    private static String formatExpiry(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return "غير محدد";
        try {
            Instant i = Instant.parse(iso);
            return DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(i);
        } catch (Exception e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
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
