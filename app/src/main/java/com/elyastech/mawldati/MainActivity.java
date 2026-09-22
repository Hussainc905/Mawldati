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

import org.json.JSONArray;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String SUPABASE_URL = "https://admwtddiylyofpwtauui.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_0CY42ztOEqpVSUEDBtJV5w_a_8W6Zg7";
    private static final String APP_VERSION = "0.3.0";
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
    private static final String P_SUBSCRIBERS = "subscribers_json";
    private static final String P_EXPENSES = "expenses_json";

    private SharedPreferences prefs;
    private ExecutorService executor;
    private String deviceId;
    private EditText activationCode;
    private Button activationButton;

    private String currentStatus = "active";
    private boolean currentOffline = false;
    private int currentGraceDays = 3;
    private String currentScreen = "home";

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

                if (ok) ed.putLong(P_LAST_OK, System.currentTimeMillis());
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
        currentScreen = "loading";
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
        currentScreen = "activation";
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
        currentScreen = "home";
        currentStatus = status;
        currentOffline = offline;
        currentGraceDays = graceDays;

        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addHeader(root, true);

        String owner = prefs.getString(P_OWNER, "");
        String generator = prefs.getString(P_GENERATOR, "");

        TextView welcome = text(owner == null || owner.isEmpty() ? "مرحباً بك" : "مرحباً، " + owner, 24, 0xFF0B1830, true);
        root.addView(welcome, matchWrap());

        TextView generatorText = text(generator == null || generator.isEmpty() ? "مولدتي" : "المولدة: " + generator, 15, 0xFF64748B, false);
        LinearLayout.LayoutParams genLp = matchWrap();
        genLp.setMargins(0, dp(3), 0, dp(14));
        root.addView(generatorText, genLp);

        int statusColor = "trial".equals(status) ? 0xFFF59E0B : 0xFF16A34A;
        String statusLabel = "trial".equals(status) ? "تجريبي" : "فعال";
        if (offline) {
            statusColor = 0xFFF59E0B;
            statusLabel += " • دون إنترنت";
        }

        LinearLayout licenseStrip = card();
        licenseStrip.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView ls = text("●  الترخيص " + statusLabel, 16, statusColor, true);
        licenseStrip.addView(ls, matchWrap());
        Button licenseDetails = smallLinkButton("تفاصيل الاشتراك");
        licenseDetails.setOnClickListener(v -> renderLicenseDetails());
        LinearLayout.LayoutParams ldp = new LinearLayout.LayoutParams(-1, dp(40));
        ldp.setMargins(0, dp(8), 0, 0);
        licenseStrip.addView(licenseDetails, ldp);
        root.addView(licenseStrip, matchWrap());

        JSONArray subs = getSubscribers();
        int total = subs.length();
        int paid = countPaidCurrentMonth(subs);
        int unpaid = Math.max(0, total - paid);
        double collected = sumCollectedCurrentMonth(subs);

        TextView dashTitle = text("نظرة سريعة", 20, 0xFF0B1830, true);
        LinearLayout.LayoutParams dtp = matchWrap();
        dtp.setMargins(0, dp(18), 0, dp(10));
        root.addView(dashTitle, dtp);

        LinearLayout row1 = horizontalRow();
        addStatCard(row1, "المشتركين", String.valueOf(total), 0xFF0F766E);
        addStatCard(row1, "الدافعين", String.valueOf(paid), 0xFF16A34A);
        root.addView(row1, matchWrap());

        LinearLayout row2 = horizontalRow();
        addStatCard(row2, "غير الدافعين", String.valueOf(unpaid), 0xFFDC2626);
        addStatCard(row2, "المقبوض", money(collected), 0xFF2563EB);
        LinearLayout.LayoutParams r2p = matchWrap();
        r2p.setMargins(0, dp(10), 0, 0);
        root.addView(row2, r2p);

        TextView servicesTitle = text("إدارة المولدة", 20, 0xFF0B1830, true);
        LinearLayout.LayoutParams stp = matchWrap();
        stp.setMargins(0, dp(20), 0, dp(10));
        root.addView(servicesTitle, stp);

        LinearLayout m1 = horizontalRow();
        addMenuButton(m1, "المشتركين", "إضافة وتعديل المشتركين", 0xFF0F766E, v -> renderSubscribers());
        addMenuButton(m1, "الجباية", "تسجيل الدفع الشهري", 0xFF2563EB, v -> renderCollections());
        root.addView(m1, matchWrap());

        LinearLayout m2 = horizontalRow();
        addMenuButton(m2, "المصروفات", "وقود وصيانة ومصاريف", 0xFFF59E0B, v -> renderExpenses());
        addMenuButton(m2, "التقارير", "ملخص الشهر والأرباح", 0xFF7C3AED, v -> renderReports());
        LinearLayout.LayoutParams m2p = matchWrap();
        m2p.setMargins(0, dp(10), 0, 0);
        root.addView(m2, m2p);

        LinearLayout m3 = horizontalRow();
        addMenuButton(m3, "الاشتراك", "حالة ترخيص التطبيق", 0xFF16A34A, v -> renderLicenseDetails());
        addMenuButton(m3, "الإعدادات", "بيانات الجهاز والدعم", 0xFF475569, v -> renderSettings());
        LinearLayout.LayoutParams m3p = matchWrap();
        m3p.setMargins(0, dp(10), 0, 0);
        root.addView(m3, m3p);

        addFooter(root);
        setContentView(scroll);
    }

    private void renderSubscribers() {
        currentScreen = "subscribers";
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addSectionHeader(root, "المشتركين", "إدارة أسماء المشتركين وأسعار الاشتراك الشهري");

        Button add = primaryButton("+ إضافة مشترك");
        add.setOnClickListener(v -> showSubscriberDialog(null));
        root.addView(add, new LinearLayout.LayoutParams(-1, dp(50)));

        JSONArray arr = getSubscribers();
        if (arr.length() == 0) {
            root.addView(emptyCard("لا يوجد مشتركون حتى الآن", "اضغط إضافة مشترك لبدء العمل."), spacedCardLp());
        } else {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                root.addView(subscriberCard(s), spacedCardLp());
            }
        }
        addFooter(root);
        setContentView(scroll);
    }

    private LinearLayout subscriberCard(JSONObject s) {
        LinearLayout c = card();
        String name = s.optString("name", "بدون اسم");
        String phone = s.optString("phone", "");
        String area = s.optString("area", "");
        int amps = s.optInt("amps", 0);
        double fee = s.optDouble("fee", 0);
        boolean paid = isPaidCurrentMonth(s);

        TextView n = text(name, 19, 0xFF0B1830, true);
        c.addView(n, matchWrap());
        String details = (area.isEmpty() ? "" : area + " • ") + (amps > 0 ? amps + " أمبير • " : "") + money(fee);
        TextView d = text(details, 14, 0xFF64748B, false);
        LinearLayout.LayoutParams dp1 = matchWrap(); dp1.setMargins(0, dp(4), 0, 0); c.addView(d, dp1);
        if (!phone.isEmpty()) {
            TextView ph = text(phone, 14, 0xFF475569, false);
            LinearLayout.LayoutParams pp = matchWrap(); pp.setMargins(0, dp(3), 0, 0); c.addView(ph, pp);
        }
        TextView ps = text("●  " + (paid ? "مدفوع هذا الشهر" : "غير مدفوع هذا الشهر"), 14, paid ? 0xFF16A34A : 0xFFDC2626, true);
        LinearLayout.LayoutParams psp = matchWrap(); psp.setMargins(0, dp(8), 0, dp(10)); c.addView(ps, psp);

        LinearLayout actions = horizontalRow();
        Button edit = outlineButton("تعديل");
        edit.setOnClickListener(v -> showSubscriberDialog(s));
        actions.addView(edit, halfButtonLp());
        Button del = outlineButton("حذف");
        del.setTextColor(0xFFB91C1C);
        del.setOnClickListener(v -> confirmDeleteSubscriber(s.optString("id")));
        LinearLayout.LayoutParams dl = halfButtonLp(); dl.setMargins(dp(6), 0, 0, 0); actions.addView(del, dl);
        c.addView(actions, matchWrap());
        return c;
    }

    private void showSubscriberDialog(JSONObject existing) {
        LinearLayout box = dialogBox();
        EditText name = dialogEdit("اسم المشترك", InputType.TYPE_CLASS_TEXT);
        EditText phone = dialogEdit("رقم الهاتف", InputType.TYPE_CLASS_PHONE);
        EditText area = dialogEdit("المنطقة / المحلة", InputType.TYPE_CLASS_TEXT);
        EditText amps = dialogEdit("عدد الأمبيرات", InputType.TYPE_CLASS_NUMBER);
        EditText fee = dialogEdit("الاشتراك الشهري بالدينار", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(name); box.addView(phone); box.addView(area); box.addView(amps); box.addView(fee);

        if (existing != null) {
            name.setText(existing.optString("name", ""));
            phone.setText(existing.optString("phone", ""));
            area.setText(existing.optString("area", ""));
            int a = existing.optInt("amps", 0); if (a > 0) amps.setText(String.valueOf(a));
            double f = existing.optDouble("fee", 0); if (f > 0) fee.setText(String.format(Locale.US, "%.0f", f));
        }

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "إضافة مشترك" : "تعديل المشترك")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Toast.makeText(this, "اسم المشترك مطلوب", Toast.LENGTH_SHORT).show(); return; }
                    JSONObject obj = existing == null ? new JSONObject() : existing;
                    try {
                        if (existing == null) obj.put("id", UUID.randomUUID().toString());
                        obj.put("name", n);
                        obj.put("phone", phone.getText().toString().trim());
                        obj.put("area", area.getText().toString().trim());
                        obj.put("amps", safeInt(amps.getText().toString()));
                        obj.put("fee", safeDouble(fee.getText().toString()));
                        JSONArray arr = getSubscribers();
                        if (existing == null) arr.put(obj);
                        else replaceById(arr, obj);
                        saveSubscribers(arr);
                        renderSubscribers();
                    } catch (Exception e) {
                        Toast.makeText(this, "تعذر حفظ المشترك", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void confirmDeleteSubscriber(String id) {
        new AlertDialog.Builder(this)
                .setTitle("حذف المشترك")
                .setMessage("هل تريد حذف هذا المشترك نهائيًا؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (d, w) -> {
                    JSONArray arr = getSubscribers();
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.optJSONObject(i);
                        if (s != null && !id.equals(s.optString("id"))) out.put(s);
                    }
                    saveSubscribers(out);
                    renderSubscribers();
                }).show();
    }

    private void renderCollections() {
        currentScreen = "collections";
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        String month = currentMonth();
        addSectionHeader(root, "الجباية", "تسجيل اشتراكات شهر " + displayMonth(month));

        JSONArray arr = getSubscribers();
        double expected = sumExpected(arr);
        double collected = sumCollectedCurrentMonth(arr);
        LinearLayout summary = card();
        addInfoRow(summary, "المطلوب", money(expected));
        addInfoRow(summary, "المقبوض", money(collected));
        addInfoRow(summary, "المتبقي", money(Math.max(0, expected - collected)));
        root.addView(summary, matchWrap());

        if (arr.length() == 0) {
            root.addView(emptyCard("لا يوجد مشتركون", "أضف المشتركين أولًا من صفحة المشتركين."), spacedCardLp());
        } else {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                root.addView(collectionCard(s), spacedCardLp());
            }
        }
        addFooter(root);
        setContentView(scroll);
    }

    private LinearLayout collectionCard(JSONObject s) {
        LinearLayout c = card();
        boolean paid = isPaidCurrentMonth(s);
        String name = s.optString("name", "بدون اسم");
        double fee = s.optDouble("fee", 0);
        double paidAmount = s.optDouble("paid_amount", fee);

        c.addView(text(name, 18, 0xFF0B1830, true), matchWrap());
        TextView amount = text(paid ? "مدفوع: " + money(paidAmount) : "المطلوب: " + money(fee), 14, paid ? 0xFF16A34A : 0xFF64748B, false);
        LinearLayout.LayoutParams ap = matchWrap(); ap.setMargins(0, dp(4), 0, dp(10)); c.addView(amount, ap);

        if (!paid) {
            Button pay = primaryButton("تسجيل الدفع");
            pay.setOnClickListener(v -> showPaymentDialog(s));
            c.addView(pay, new LinearLayout.LayoutParams(-1, dp(46)));
        } else {
            LinearLayout actions = horizontalRow();
            Button receipt = outlineButton("إرسال إيصال");
            receipt.setOnClickListener(v -> shareReceipt(s));
            actions.addView(receipt, halfButtonLp());
            Button undo = outlineButton("إلغاء الدفع");
            undo.setTextColor(0xFFB91C1C);
            undo.setOnClickListener(v -> undoPayment(s));
            LinearLayout.LayoutParams ul = halfButtonLp(); ul.setMargins(dp(6), 0, 0, 0); actions.addView(undo, ul);
            c.addView(actions, matchWrap());
        }
        return c;
    }

    private void showPaymentDialog(JSONObject s) {
        EditText amount = dialogEdit("المبلغ المستلم", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double fee = s.optDouble("fee", 0);
        if (fee > 0) amount.setText(String.format(Locale.US, "%.0f", fee));
        LinearLayout box = dialogBox(); box.addView(amount);
        new AlertDialog.Builder(this)
                .setTitle("تسجيل دفع - " + s.optString("name", ""))
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d,w) -> {
                    try {
                        s.put("paid_month", currentMonth());
                        s.put("paid_amount", safeDouble(amount.getText().toString()));
                        s.put("paid_at", LocalDate.now().toString());
                        JSONArray arr = getSubscribers();
                        replaceById(arr, s);
                        saveSubscribers(arr);
                        renderCollections();
                    } catch (Exception e) {
                        Toast.makeText(this, "تعذر تسجيل الدفع", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void undoPayment(JSONObject s) {
        new AlertDialog.Builder(this)
                .setTitle("إلغاء الدفع")
                .setMessage("سيعود المشترك إلى غير مدفوع لهذا الشهر.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة", (d,w) -> {
                    try {
                        s.remove("paid_month"); s.remove("paid_amount"); s.remove("paid_at");
                        JSONArray arr = getSubscribers(); replaceById(arr, s); saveSubscribers(arr); renderCollections();
                    } catch (Exception ignored) {}
                }).show();
    }

    private void shareReceipt(JSONObject s) {
        String generator = prefs.getString(P_GENERATOR, "مولدتي");
        String message = "إيصال اشتراك مولدة\n" +
                "المولدة: " + generator + "\n" +
                "المشترك: " + s.optString("name", "") + "\n" +
                "الشهر: " + displayMonth(currentMonth()) + "\n" +
                "المبلغ: " + money(s.optDouble("paid_amount", s.optDouble("fee", 0))) + "\n" +
                "تاريخ الدفع: " + s.optString("paid_at", LocalDate.now().toString()) + "\n" +
                "ELYAS-TECH - " + SUPPORT_PHONE;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, message);
        try { startActivity(Intent.createChooser(intent, "إرسال الإيصال")); }
        catch (Exception e) { Toast.makeText(this, "تعذر فتح تطبيق المشاركة", Toast.LENGTH_SHORT).show(); }
    }

    private void renderExpenses() {
        currentScreen = "expenses";
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addSectionHeader(root, "المصروفات", "وقود، صيانة، زيوت وأي مصروف آخر");

        Button add = primaryButton("+ إضافة مصروف");
        add.setOnClickListener(v -> showExpenseDialog());
        root.addView(add, new LinearLayout.LayoutParams(-1, dp(50)));

        JSONArray arr = getExpenses();
        double totalMonth = sumExpensesCurrentMonth(arr);
        LinearLayout totalCard = card();
        totalCard.addView(text("مصروفات هذا الشهر", 15, 0xFF64748B, false), matchWrap());
        TextView tv = text(money(totalMonth), 24, 0xFFB45309, true);
        LinearLayout.LayoutParams tp = matchWrap(); tp.setMargins(0, dp(5), 0, 0); totalCard.addView(tv, tp);
        root.addView(totalCard, spacedCardLp());

        boolean any = false;
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null || !isCurrentMonthDate(e.optString("date", ""))) continue;
            any = true;
            LinearLayout c = card();
            c.addView(text(e.optString("note", "مصروف"), 17, 0xFF0B1830, true), matchWrap());
            TextView line = text(money(e.optDouble("amount", 0)) + " • " + e.optString("date", ""), 14, 0xFF64748B, false);
            LinearLayout.LayoutParams lp = matchWrap(); lp.setMargins(0, dp(4), 0, dp(8)); c.addView(line, lp);
            Button del = outlineButton("حذف المصروف"); del.setTextColor(0xFFB91C1C);
            String id = e.optString("id"); del.setOnClickListener(v -> deleteExpense(id)); c.addView(del, new LinearLayout.LayoutParams(-1, dp(44)));
            root.addView(c, spacedCardLp());
        }
        if (!any) root.addView(emptyCard("لا توجد مصروفات هذا الشهر", "أضف أول مصروف عند الحاجة."), spacedCardLp());
        addFooter(root);
        setContentView(scroll);
    }

    private void showExpenseDialog() {
        LinearLayout box = dialogBox();
        EditText note = dialogEdit("نوع المصروف / الملاحظة", InputType.TYPE_CLASS_TEXT);
        EditText amount = dialogEdit("المبلغ بالدينار", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(note); box.addView(amount);
        new AlertDialog.Builder(this)
                .setTitle("إضافة مصروف")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d,w) -> {
                    try {
                        JSONObject e = new JSONObject();
                        e.put("id", UUID.randomUUID().toString());
                        e.put("note", note.getText().toString().trim().isEmpty() ? "مصروف" : note.getText().toString().trim());
                        e.put("amount", safeDouble(amount.getText().toString()));
                        e.put("date", LocalDate.now().toString());
                        JSONArray arr = getExpenses(); arr.put(e); saveExpenses(arr); renderExpenses();
                    } catch (Exception ex) { Toast.makeText(this, "تعذر حفظ المصروف", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void deleteExpense(String id) {
        JSONArray arr = getExpenses(); JSONArray out = new JSONArray();
        for (int i=0;i<arr.length();i++) { JSONObject e=arr.optJSONObject(i); if(e!=null && !id.equals(e.optString("id"))) out.put(e); }
        saveExpenses(out); renderExpenses();
    }

    private void renderReports() {
        currentScreen = "reports";
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addSectionHeader(root, "تقرير الشهر", "ملخص " + displayMonth(currentMonth()));

        JSONArray subs = getSubscribers();
        JSONArray exps = getExpenses();
        int total = subs.length();
        int paid = countPaidCurrentMonth(subs);
        double expected = sumExpected(subs);
        double collected = sumCollectedCurrentMonth(subs);
        double expenses = sumExpensesCurrentMonth(exps);
        double net = collected - expenses;

        LinearLayout c = card();
        addInfoRow(c, "عدد المشتركين", String.valueOf(total));
        addInfoRow(c, "الدافعين", paid + " / " + total);
        addInfoRow(c, "المبلغ المتوقع", money(expected));
        addInfoRow(c, "المبلغ المقبوض", money(collected));
        addInfoRow(c, "المتبقي للجباية", money(Math.max(0, expected-collected)));
        addInfoRow(c, "المصروفات", money(expenses));
        addInfoRow(c, "الصافي الحالي", money(net));
        root.addView(c, matchWrap());

        Button copy = primaryButton("نسخ تقرير الشهر");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(50)); cp.setMargins(0, dp(14), 0, 0); root.addView(copy, cp);
        copy.setOnClickListener(v -> copyMonthlyReport(total, paid, expected, collected, expenses, net));

        addFooter(root);
        setContentView(scroll);
    }

    private void copyMonthlyReport(int total, int paid, double expected, double collected, double expenses, double net) {
        String report = "تقرير مولدتي - " + displayMonth(currentMonth()) + "\n" +
                "عدد المشتركين: " + total + "\n" +
                "الدافعين: " + paid + "\n" +
                "المبلغ المتوقع: " + money(expected) + "\n" +
                "المقبوض: " + money(collected) + "\n" +
                "المصروفات: " + money(expenses) + "\n" +
                "الصافي الحالي: " + money(net);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Mawldati report", report));
        Toast.makeText(this, "تم نسخ التقرير", Toast.LENGTH_SHORT).show();
    }

    private void renderLicenseDetails() {
        currentScreen = "license";
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addSectionHeader(root, "الاشتراك والترخيص", "حالة ترخيص تطبيق مولدتي");

        String activatedAt = prefs.getString(P_ACTIVATED, "");
        String expiresAt = prefs.getString(P_EXPIRES, "");
        long daysLeft = remainingDays(expiresAt);
        int statusColor = "trial".equals(currentStatus) ? 0xFFF59E0B : 0xFF16A34A;
        String statusLabel = "trial".equals(currentStatus) ? "تجريبي" : "فعال";
        if (currentOffline) { statusColor = 0xFFF59E0B; statusLabel += " • دون إنترنت"; }

        LinearLayout statusCard = card();
        statusCard.addView(text("●  " + statusLabel, 21, statusColor, true), matchWrap());
        TextView statusSub = text(currentOffline ? "سماح مؤقت دون إنترنت ضمن مهلة " + currentGraceDays + " أيام." : "تم التحقق من الاشتراك من السيرفر بنجاح.", 14, 0xFF64748B, false);
        LinearLayout.LayoutParams sp = matchWrap(); sp.setMargins(0, dp(7), 0, 0); statusCard.addView(statusSub, sp);
        root.addView(statusCard, matchWrap());

        LinearLayout c = card();
        addInfoRow(c, "من تاريخ", activatedAt == null || activatedAt.isEmpty() ? "غير محدد" : formatDate(activatedAt));
        addInfoRow(c, "إلى تاريخ", formatDate(expiresAt));
        addInfoRow(c, "الأيام المتبقية", daysLeft < 0 ? "غير محدد" : daysLeft + " يوم");
        addInfoRow(c, "حالة الاتصال", currentOffline ? "دون إنترنت - سماح مؤقت" : "السيرفر متصل");
        root.addView(c, spacedCardLp());

        Button refresh = primaryButton("فحص الاشتراك الآن");
        refresh.setOnClickListener(v -> {
            String code = prefs.getString(P_CODE, "");
            if (code == null || code.isEmpty()) { renderActivation("التطبيق غير مفعّل", "أدخل رمز التفعيل.", 0xFF64748B); return; }
            renderLoading("جارِ تحديث الاشتراك", "يتم الاتصال بالسيرفر للحصول على أحدث حالة...");
            checkLicense(code);
        });
        root.addView(refresh, new LinearLayout.LayoutParams(-1, dp(50)));
        addFooter(root); setContentView(scroll);
    }

    private void renderSettings() {
        currentScreen = "settings";
        ScrollView scroll = newBaseScroll();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        addSectionHeader(root, "الإعدادات", "بيانات التطبيق والجهاز والدعم الفني");

        LinearLayout info = card();
        addInfoRow(info, "صاحب الاشتراك", prefs.getString(P_OWNER, "غير محدد"));
        addInfoRow(info, "اسم المولدة", prefs.getString(P_GENERATOR, "غير محدد"));
        addInfoRow(info, "الإصدار", APP_VERSION);
        root.addView(info, matchWrap());

        Button support = outlineButton("الاتصال بالدعم الفني"); support.setOnClickListener(v -> callSupport()); root.addView(support, spacedButtonLp());
        Button copyDevice = outlineButton("نسخ معرّف الجهاز"); copyDevice.setOnClickListener(v -> copyDeviceId()); root.addView(copyDevice, spacedButtonLp());
        Button changeCode = outlineButton("تغيير رمز التفعيل"); changeCode.setTextColor(0xFFB91C1C); changeCode.setOnClickListener(v -> confirmChangeCode()); root.addView(changeCode, spacedButtonLp());
        addFooter(root); setContentView(scroll);
    }

    private void addSectionHeader(LinearLayout root, String title, String subtitle) {
        addHeader(root, true);
        Button back = smallLinkButton("‹ الرجوع للرئيسية");
        back.setOnClickListener(v -> renderHome(currentStatus, currentOffline, currentGraceDays));
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView t = text(title, 25, 0xFF0B1830, true);
        LinearLayout.LayoutParams tp = matchWrap(); tp.setMargins(0, dp(14), 0, 0); root.addView(t, tp);
        TextView s = text(subtitle, 14, 0xFF64748B, false);
        LinearLayout.LayoutParams sp = matchWrap(); sp.setMargins(0, dp(3), 0, dp(14)); root.addView(s, sp);
    }

    private LinearLayout emptyCard(String title, String sub) {
        LinearLayout c = card();
        TextView t = text(title, 18, 0xFF334155, true); t.setGravity(Gravity.CENTER); c.addView(t, matchWrap());
        TextView s = text(sub, 14, 0xFF64748B, false); s.setGravity(Gravity.CENTER); LinearLayout.LayoutParams sp=matchWrap(); sp.setMargins(0,dp(5),0,0); c.addView(s,sp);
        return c;
    }

    private void addStatCard(LinearLayout row, String label, String value, int color) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(10), dp(13), dp(10), dp(13));
        TextView v = text(value, value.length() > 9 ? 15 : 22, color, true); v.setGravity(Gravity.CENTER); c.addView(v, matchWrap());
        TextView l = text(label, 12, 0xFF64748B, false); l.setGravity(Gravity.CENTER); LinearLayout.LayoutParams lp=matchWrap(); lp.setMargins(0,dp(3),0,0); c.addView(l,lp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f); cp.setMargins(dp(4),0,dp(4),0); row.addView(c,cp);
    }

    private void addMenuButton(LinearLayout row, String title, String sub, int color, View.OnClickListener listener) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER); c.setPadding(dp(10),dp(15),dp(10),dp(15)); c.setBackground(makeRounded(0xFFFFFFFF,16)); c.setOnClickListener(listener);
        TextView t = text(title,18,color,true); t.setGravity(Gravity.CENTER); c.addView(t,matchWrap());
        TextView s = text(sub,12,0xFF64748B,false); s.setGravity(Gravity.CENTER); LinearLayout.LayoutParams sp=matchWrap(); sp.setMargins(0,dp(4),0,0); c.addView(s,sp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(102), 1f); cp.setMargins(dp(4),0,dp(4),0); row.addView(c,cp);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); return row;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(6),dp(18),0); box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); return box;
    }

    private EditText dialogEdit(String hint, int inputType) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(inputType); e.setSingleLine(true); e.setTextSize(16); e.setPadding(dp(10),dp(10),dp(10),dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0,dp(5),0,dp(5)); e.setLayoutParams(lp); return e;
    }

    private JSONArray getSubscribers() {
        try { return new JSONArray(prefs.getString(P_SUBSCRIBERS, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    private void saveSubscribers(JSONArray arr) { prefs.edit().putString(P_SUBSCRIBERS, arr.toString()).apply(); }
    private JSONArray getExpenses() { try { return new JSONArray(prefs.getString(P_EXPENSES, "[]")); } catch (Exception e) { return new JSONArray(); } }
    private void saveExpenses(JSONArray arr) { prefs.edit().putString(P_EXPENSES, arr.toString()).apply(); }

    private void replaceById(JSONArray arr, JSONObject obj) throws Exception {
        String id = obj.optString("id");
        for (int i=0;i<arr.length();i++) { JSONObject s=arr.optJSONObject(i); if(s!=null && id.equals(s.optString("id"))) { arr.put(i,obj); return; } }
        arr.put(obj);
    }

    private boolean isPaidCurrentMonth(JSONObject s) { return currentMonth().equals(s.optString("paid_month", "")); }
    private int countPaidCurrentMonth(JSONArray arr) { int n=0; for(int i=0;i<arr.length();i++){ JSONObject s=arr.optJSONObject(i); if(s!=null&&isPaidCurrentMonth(s))n++; } return n; }
    private double sumExpected(JSONArray arr) { double x=0; for(int i=0;i<arr.length();i++){ JSONObject s=arr.optJSONObject(i); if(s!=null)x+=s.optDouble("fee",0); } return x; }
    private double sumCollectedCurrentMonth(JSONArray arr) { double x=0; for(int i=0;i<arr.length();i++){ JSONObject s=arr.optJSONObject(i); if(s!=null&&isPaidCurrentMonth(s))x+=s.optDouble("paid_amount",s.optDouble("fee",0)); } return x; }
    private double sumExpensesCurrentMonth(JSONArray arr) { double x=0; for(int i=0;i<arr.length();i++){ JSONObject e=arr.optJSONObject(i); if(e!=null&&isCurrentMonthDate(e.optString("date","")))x+=e.optDouble("amount",0); } return x; }
    private boolean isCurrentMonthDate(String date) { return date != null && date.startsWith(currentMonth()); }
    private String currentMonth() { return YearMonth.now().toString(); }
    private String displayMonth(String ym) { try { YearMonth m=YearMonth.parse(ym); return String.format(Locale.US, "%02d/%d",m.getMonthValue(),m.getYear()); } catch(Exception e){ return ym; } }
    private String money(double v) { return String.format(Locale.US, "%,.0f د.ع", v); }
    private int safeInt(String s) { try { return Integer.parseInt(s.trim()); } catch(Exception e){ return 0; } }
    private double safeDouble(String s) { try { return Double.parseDouble(s.trim().replace(",","")); } catch(Exception e){ return 0; } }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout.LayoutParams rowLp = matchWrap();
        rowLp.setMargins(0, dp(12), 0, 0);
        parent.addView(row, rowLp);

        TextView l = text(label, 14, 0xFF64748B, false);
        TextView v = text(value == null || value.isEmpty() ? "غير محدد" : value, 15, 0xFF1E293B, true);
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
        int h = compact ? 70 : 105;
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(-1, dp(h));
        logoLp.setMargins(0, 0, 0, dp(8));
        root.addView(logo, logoLp);

        TextView title = text("مولدتي", compact ? 23 : 30, 0xFF0B1830, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text("ELYAS-TECH • إدارة المولدة والاشتراكات", 14, 0xFF64748B, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.setMargins(0, dp(3), 0, dp(compact ? 14 : 22));
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

    private Button smallLinkButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(14); b.setTextColor(0xFF0F766E); b.setAllCaps(false); b.setBackground(makeRounded(0xFFF0FDFA, 10)); return b;
    }

    private LinearLayout.LayoutParams spacedCardLp() { LinearLayout.LayoutParams lp=matchWrap(); lp.setMargins(0,dp(12),0,0); return lp; }
    private LinearLayout.LayoutParams spacedButtonLp() { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48)); lp.setMargins(0,dp(10),0,0); return lp; }
    private LinearLayout.LayoutParams halfButtonLp() { return new LinearLayout.LayoutParams(0,dp(44),1f); }

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
            LocalDate end = parseDatePart(iso);
            if (end != null) return !end.isBefore(LocalDate.now());
            return Instant.parse(iso).isAfter(Instant.now());
        } catch (Exception e) { return true; }
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return "غير محدد";
        LocalDate date = parseDatePart(iso);
        if (date != null) return date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US));
        try {
            Instant i = Instant.parse(iso);
            return DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US).withZone(ZoneId.systemDefault()).format(i);
        } catch (Exception e) { return iso; }
    }

    private static long remainingDays(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return -1;
        LocalDate end = parseDatePart(iso);
        if (end == null) {
            try { end = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate(); }
            catch (Exception ignored) { return -1; }
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), end);
        return Math.max(0, days);
    }

    private static LocalDate parseDatePart(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (Exception ignored) { return null; }
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

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private android.graphics.drawable.GradientDrawable makeRounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    public void onBackPressed() {
        if ("home".equals(currentScreen) || "activation".equals(currentScreen) || "loading".equals(currentScreen)) {
            super.onBackPressed();
        } else {
            renderHome(currentStatus, currentOffline, currentGraceDays);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
