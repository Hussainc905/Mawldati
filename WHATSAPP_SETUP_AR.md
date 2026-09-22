# WhatsApp Business + الوصل المصور — Mawldati v0.3.6

هذه النسخة ترسل **صورة الوصل الرسمية** مباشرة إلى رقم واتساب المحفوظ للمشترك، ولا تفتح تطبيق واتساب على الهاتف/التابلت.

## المطلوب
- Meta Business + WhatsApp Business Platform / Cloud API.
- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_PHONE_NUMBER_ID`
- قالب Utility معتمد باسم `payment_receipt_image` أو غيّر Secret الخاص بالاسم.

## شكل القالب
- Header: **Image** (dynamic)
- Body variables:
  1. رقم الوصل
  2. اسم المشترك
  3. الشهر
  4. المبلغ

النص المقترح:

وصل اشتراك مولدتي
رقم الوصل: {{1}}
المشترك: {{2}}
الشهر: {{3}}
المبلغ: {{4}}

الصورة الموجودة في Header هي الوصل الرسمي نفسه وتحتوي اللوكو، بيانات المشترك، الأمبير، التاريخ، الرقم التسلسلي وQR.

## Edge Function Secrets
- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_PHONE_NUMBER_ID`
- `WHATSAPP_TEMPLATE_NAME=payment_receipt_image`
- `WHATSAPP_TEMPLATE_LANG=ar`
- `WHATSAPP_GRAPH_VERSION` وفق لوحة Meta

## ملاحظة
ملف `supabase/config.toml` يضبط دالتي الإرسال والتحقق كدوال عامة بدون JWT لأن التطبيق وصفحة QR تتحققان بطريقتهما الخاصة من الترخيص/رمز التحقق.
