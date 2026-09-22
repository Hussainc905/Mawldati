// Mawldati v0.3.6 - sends the official PNG receipt directly through WhatsApp Cloud API.
// Required secrets:
// WHATSAPP_ACCESS_TOKEN
// WHATSAPP_PHONE_NUMBER_ID
// WHATSAPP_TEMPLATE_NAME   default: payment_receipt_image
// WHATSAPP_TEMPLATE_LANG   default: ar
// WHATSAPP_GRAPH_VERSION   default: v23.0

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

function normalizePhone(value: string): string {
  let d = (value || "").replace(/\D/g, "");
  if (d.startsWith("00")) d = d.slice(2);
  if (d.startsWith("0")) d = "964" + d.slice(1);
  else if (!d.startsWith("964") && d.length === 10) d = "964" + d;
  return d.startsWith("964") && d.length >= 12 && d.length <= 13 ? d : "";
}

function decodeBase64(input: string): Uint8Array {
  const clean = input.includes(",") ? input.split(",").pop() || "" : input;
  const bin = atob(clean);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
    const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    const token = Deno.env.get("WHATSAPP_ACCESS_TOKEN") || "";
    const phoneNumberId = Deno.env.get("WHATSAPP_PHONE_NUMBER_ID") || "";
    const templateName = Deno.env.get("WHATSAPP_TEMPLATE_NAME") || "payment_receipt_image";
    const templateLang = Deno.env.get("WHATSAPP_TEMPLATE_LANG") || "ar";
    const graphVersion = Deno.env.get("WHATSAPP_GRAPH_VERSION") || "v23.0";

    if (!supabaseUrl || !serviceRole || !token || !phoneNumberId) {
      return json({ ok: false, error: "whatsapp_not_configured" }, 503);
    }

    const body = await req.json();
    const activationCode = String(body.activation_code || "").trim().toUpperCase();
    const deviceId = String(body.device_id || "").trim();
    const receiptNo = String(body.receipt_no || "").trim();
    const imageBase64 = String(body.image_base64 || "");
    if (!activationCode || !deviceId || !receiptNo || !imageBase64) return json({ ok: false, error: "receipt_data_missing" }, 400);

    const licenseRes = await fetch(`${supabaseUrl}/rest/v1/rpc/check_license`, {
      method: "POST",
      headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, "Content-Type": "application/json" },
      body: JSON.stringify({ p_activation_code: activationCode, p_device_id: deviceId, p_app_version: "0.3.6-edge" }),
    });
    if (!licenseRes.ok) return json({ ok: false, error: "license_check_failed" }, 403);
    const license = await licenseRes.json();
    if (!license?.ok || !["active", "trial"].includes(String(license.status))) return json({ ok: false, error: "license_not_active" }, 403);

    const qr = `${supabaseUrl}/rest/v1/receipts?receipt_no=eq.${encodeURIComponent(receiptNo)}&activation_code=eq.${encodeURIComponent(activationCode)}&select=id,receipt_no,subscriber_name,subscriber_phone,generator_name,paid_month,amount,paid_at,status&limit=1`;
    const rr = await fetch(qr, { headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}` } });
    const rows = await rr.json();
    const receipt = Array.isArray(rows) ? rows[0] : null;
    if (!receipt) return json({ ok: false, error: "receipt_not_found" }, 404);
    if (receipt.status !== "issued") return json({ ok: false, error: "receipt_cancelled" }, 409);

    const to = normalizePhone(String(receipt.subscriber_phone || ""));
    if (!to) return json({ ok: false, error: "invalid_phone" }, 400);

    const image = decodeBase64(imageBase64);
    if (image.length > 5_000_000) return json({ ok: false, error: "receipt_image_too_large" }, 413);
    const path = `${activationCode}/${receiptNo}.png`;

    const upload = await fetch(`${supabaseUrl}/storage/v1/object/receipt-images/${encodeURIComponent(path).replaceAll('%2F','/')}`, {
      method: "PUT",
      headers: {
        apikey: serviceRole,
        Authorization: `Bearer ${serviceRole}`,
        "Content-Type": "image/png",
        "x-upsert": "true",
      },
      body: image,
    });
    if (!upload.ok) return json({ ok: false, error: "receipt_image_upload_failed", details: await upload.text() }, 502);

    const sign = await fetch(`${supabaseUrl}/storage/v1/object/sign/receipt-images/${encodeURIComponent(path).replaceAll('%2F','/')}`, {
      method: "POST",
      headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, "Content-Type": "application/json" },
      body: JSON.stringify({ expiresIn: 900 }),
    });
    const signJson = await sign.json();
    if (!sign.ok || !signJson?.signedURL) return json({ ok: false, error: "receipt_image_sign_failed", details: signJson }, 502);
    const imageUrl = String(signJson.signedURL).startsWith("http") ? signJson.signedURL : `${supabaseUrl}/storage/v1${signJson.signedURL}`;

    const waBody = {
      messaging_product: "whatsapp",
      to,
      type: "template",
      template: {
        name: templateName,
        language: { code: templateLang },
        components: [
          { type: "header", parameters: [{ type: "image", image: { link: imageUrl } }] },
          { type: "body", parameters: [
            { type: "text", text: String(receipt.receipt_no) },
            { type: "text", text: String(receipt.subscriber_name || "-") },
            { type: "text", text: String(receipt.paid_month || "-") },
            { type: "text", text: String(receipt.amount || "0") + " د.ع" },
          ] },
        ],
      },
    };

    const waRes = await fetch(`https://graph.facebook.com/${graphVersion}/${phoneNumberId}/messages`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify(waBody),
    });
    const waJson = await waRes.json();
    if (!waRes.ok) return json({ ok: false, error: "whatsapp_send_failed", details: waJson }, 502);
    const messageId = waJson?.messages?.[0]?.id || null;

    await fetch(`${supabaseUrl}/rest/v1/receipts?id=eq.${encodeURIComponent(receipt.id)}`, {
      method: "PATCH",
      headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, "Content-Type": "application/json", Prefer: "return=minimal" },
      body: JSON.stringify({ image_path: path, whatsapp_message_id: messageId }),
    });

    return json({ ok: true, to, receipt_no: receiptNo, message_id: messageId });
  } catch (e) {
    return json({ ok: false, error: "server_error", details: String(e?.message || e) }, 500);
  }
});
