const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

function esc(v: unknown) {
  return String(v ?? "").replace(/[&<>\"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c] || c));
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  const url = new URL(req.url);
  const token = url.searchParams.get("token") || "";
  const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
  const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  if (!token || !supabaseUrl || !serviceRole) return new Response("Invalid receipt", { status: 400 });

  const q = `${supabaseUrl}/rest/v1/receipts?verify_token=eq.${encodeURIComponent(token)}&select=receipt_no,subscriber_name,subscriber_phone,subscriber_area,amps,generator_name,paid_month,amount,paid_at,status,created_at&limit=1`;
  const r = await fetch(q, { headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}` } });
  const rows = await r.json();
  const x = Array.isArray(rows) ? rows[0] : null;
  if (!x) return new Response("Receipt not found", { status: 404 });

  const ok = x.status === "issued";
  const html = `<!doctype html><html dir="rtl" lang="ar"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>تحقق الوصل</title><style>body{font-family:Arial,sans-serif;background:#f4f7fb;color:#0b1830;margin:0;padding:24px}.card{max-width:620px;margin:auto;background:#fff;border-radius:18px;padding:24px;box-shadow:0 10px 35px #0001}.state{font-size:28px;font-weight:700;color:${ok?'#15803d':'#b91c1c'};margin:10px 0 20px}.row{display:flex;justify-content:space-between;gap:20px;padding:12px 0;border-bottom:1px solid #e2e8f0}.no{font-size:24px;font-weight:800;color:#b91c1c;background:#fff1f2;padding:12px;border-radius:12px;text-align:center}</style></head><body><div class="card"><h1>مولدتي</h1><div class="state">${ok?'✓ وصل صحيح وفعال':'✕ الوصل ملغي'}</div><div class="no">${esc(x.receipt_no)}</div><div class="row"><b>المشترك</b><span>${esc(x.subscriber_name)}</span></div><div class="row"><b>المولدة</b><span>${esc(x.generator_name)}</span></div><div class="row"><b>الهاتف</b><span>${esc(x.subscriber_phone)}</span></div><div class="row"><b>المنطقة</b><span>${esc(x.subscriber_area)}</span></div><div class="row"><b>الأمبير</b><span>${esc(x.amps)}</span></div><div class="row"><b>الشهر</b><span>${esc(x.paid_month)}</span></div><div class="row"><b>المبلغ</b><span>${esc(x.amount)} د.ع</span></div><div class="row"><b>وقت الدفع</b><span>${esc(x.paid_at)}</span></div></div></body></html>`;
  return new Response(html, { headers: { ...corsHeaders, "Content-Type": "text/html; charset=utf-8" } });
});
