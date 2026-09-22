# إعداد واتساب المباشر — مولدتي v0.3.7

التطبيق لا يفتح تطبيق واتساب على الجهاز. الإرسال يتم من السيرفر عبر WhatsApp Business Platform / Cloud API.

## المطلوب في Supabase Edge Functions
انشر دالة واحدة فقط:
- `send-whatsapp-receipt`

لا تنشئ `verify-receipt`.

## Secrets المطلوبة
- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_PHONE_NUMBER_ID`
- `WHATSAPP_TEMPLATE_NAME` (الافتراضي `payment_receipt_image`)
- `WHATSAPP_TEMPLATE_LANG` (الافتراضي `ar`)
- `WHATSAPP_GRAPH_VERSION` (الافتراضي `v23.0`)

## قالب واتساب
قالب Utility بصورة في Header. صورة الـHeader هي الوصل الرسمي، وتحتوي على اللوكو ورقم الوصل وبيانات المشترك والتفاصيل. لا يوجد QR.
