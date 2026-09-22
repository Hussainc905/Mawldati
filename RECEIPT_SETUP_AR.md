# إعداد الوصل الرسمي - Mawldati v0.3.6

## ما الجديد؟
- رقم وصل فريد يصدر من Supabase، مثل `ELY-2026-000001`.
- لا يستطيع تطبيق Android اختيار رقم الوصل أو تعديله.
- كل وصل يحفظ كسجل مستقل في جدول `receipts`.
- عند إلغاء الدفع لا يحذف الوصل؛ تتحول حالته إلى `cancelled` ويبقى في سجل التدقيق.
- QR داخل صورة الوصل يفتح صفحة تحقق مباشرة من السيرفر.
- الوصل يُنشأ كصورة PNG رسمية داخل التطبيق ثم يرسلها السيرفر مباشرة إلى واتساب المشترك.

## 1) تحديث قاعدة البيانات
افتح Supabase > SQL Editor > New query، والصق محتوى:

`supabase_v0_3_6.sql`

ثم Run.

## 2) نشر Edge Functions
انشر الدالتين:

- `verify-receipt`
- `send-whatsapp-receipt`

## 3) إعداد WhatsApp Business Platform
الدالة تحتاج الأسرار التالية:

- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_PHONE_NUMBER_ID`
- `WHATSAPP_TEMPLATE_NAME=payment_receipt_image`
- `WHATSAPP_TEMPLATE_LANG=ar`
- `WHATSAPP_GRAPH_VERSION` حسب إصدار Graph API في Meta

## 4) قالب واتساب
أنشئ قالب Utility باسم `payment_receipt_image`، ويحتوي على:

- Header: Image
- Body variables:
  1. رقم الوصل
  2. اسم المشترك
  3. الشهر
  4. المبلغ

الصورة نفسها تتضمن جميع التفاصيل واللوكو ورقم الوصل وQR.

## ملاحظة أمنية
الرقم والتاريخ والحالة المرجعية تحفظ في Supabase، لذلك إعادة تثبيت التطبيق أو تعديل التخزين المحلي لا يغير السجل الرسمي في السيرفر. بيانات المشترك التشغيلية الأساسية ما زالت محلية في هذه المرحلة، لذلك الحماية الأقوى لاحقًا ستكون بنقل سجل المشتركين والجباية نفسه إلى Supabase أيضًا.
