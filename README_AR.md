# مولدتي - Mawldati Android

تطبيق Android تابع لـ ELYAS-TECH ومربوط بنظام التراخيص في Supabase.

## البناء على GitHub

المشروع مجهز بـ GitHub Actions. عند رفع الملفات إلى فرع `main` يبدأ البناء تلقائياً.

1. افتح تبويب **Actions** في المستودع.
2. اختر **Build Mawldati Android APK**.
3. بعد نجاح التشغيل افتح التشغيل الأخير.
4. من **Artifacts** حمّل `Mawldati-v0.1-debug-apk`.
5. بعد فك الضغط ستجد `app-debug.apk`.

يمكن أيضاً تشغيل البناء يدوياً من زر **Run workflow**.

## ملاحظة مهمة

يجب أن تكون هذه الملفات في جذر المستودع مباشرة:

- `settings.gradle`
- `build.gradle`
- مجلد `app`
- مجلد `.github`

لا ترفع مجلداً خارجياً يحتوي المشروع داخله، حتى لا تصبح مسارات GitHub Actions خاطئة.
