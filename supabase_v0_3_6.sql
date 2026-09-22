-- Mawldati v0.3.6
-- Official server-issued receipts + immutable receipt numbers + QR verification

create extension if not exists pgcrypto;

create sequence if not exists public.receipt_sequence start 1;

create table if not exists public.receipts (
  id uuid primary key default gen_random_uuid(),
  receipt_no text not null unique,
  activation_code text not null,
  device_id text not null,
  subscriber_local_id text not null,
  subscriber_name text not null,
  subscriber_phone text,
  subscriber_area text,
  amps integer not null default 0,
  generator_name text not null default 'مولدتي',
  paid_month text not null,
  amount numeric(14,2) not null default 0,
  paid_at timestamptz not null default now(),
  status text not null default 'issued' check (status in ('issued','cancelled')),
  verify_token uuid not null unique default gen_random_uuid(),
  image_path text,
  whatsapp_message_id text,
  cancelled_at timestamptz,
  created_at timestamptz not null default now()
);

create unique index if not exists receipts_one_active_per_payment
on public.receipts (activation_code, subscriber_local_id, paid_month)
where status = 'issued';

alter table public.receipts enable row level security;

-- No direct anon/authenticated policies are intentionally added.
-- Receipt creation/cancellation is only allowed through SECURITY DEFINER RPC functions.

create or replace function public.issue_receipt(
  p_activation_code text,
  p_device_id text,
  p_subscriber_local_id text,
  p_subscriber_name text,
  p_subscriber_phone text,
  p_subscriber_area text,
  p_amps integer,
  p_generator_name text,
  p_paid_month text,
  p_amount numeric,
  p_paid_at text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_client public.clients%rowtype;
  v_receipt public.receipts%rowtype;
  v_no text;
  v_paid_at timestamptz;
  v_base_url text := 'https://admwtddiylyofpwtauui.supabase.co/functions/v1/verify-receipt?token=';
begin
  if coalesce(trim(p_activation_code),'') = '' or coalesce(trim(p_device_id),'') = '' then
    return jsonb_build_object('ok', false, 'error', 'license_data_missing');
  end if;

  select * into v_client
  from public.clients
  where upper(activation_code) = upper(trim(p_activation_code))
  limit 1;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'license_not_found');
  end if;

  if v_client.suspended or v_client.expires_at < now() then
    return jsonb_build_object('ok', false, 'error', 'license_not_active');
  end if;

  if v_client.bound_device_id is null or v_client.bound_device_id <> trim(p_device_id) then
    return jsonb_build_object('ok', false, 'error', 'device_mismatch');
  end if;

  if coalesce(trim(p_subscriber_local_id),'') = '' or coalesce(trim(p_subscriber_name),'') = '' then
    return jsonb_build_object('ok', false, 'error', 'subscriber_data_missing');
  end if;

  if coalesce(trim(p_paid_month),'') = '' then
    return jsonb_build_object('ok', false, 'error', 'paid_month_missing');
  end if;

  if coalesce(p_amount, 0) <= 0 then
    return jsonb_build_object('ok', false, 'error', 'invalid_amount');
  end if;

  -- Reuse an already-issued receipt for the same subscriber/month.
  select * into v_receipt
  from public.receipts
  where upper(activation_code) = upper(trim(p_activation_code))
    and subscriber_local_id = trim(p_subscriber_local_id)
    and paid_month = trim(p_paid_month)
    and status = 'issued'
  order by created_at desc
  limit 1;

  if found then
    return jsonb_build_object(
      'ok', true,
      'existing', true,
      'receipt_id', v_receipt.id,
      'receipt_no', v_receipt.receipt_no,
      'subscriber_name', v_receipt.subscriber_name,
      'subscriber_phone', v_receipt.subscriber_phone,
      'subscriber_area', v_receipt.subscriber_area,
      'amps', v_receipt.amps,
      'generator_name', v_receipt.generator_name,
      'paid_month', v_receipt.paid_month,
      'amount', v_receipt.amount,
      'paid_at', v_receipt.paid_at,
      'status', v_receipt.status,
      'verify_url', v_base_url || v_receipt.verify_token::text
    );
  end if;

  begin
    v_paid_at := nullif(trim(p_paid_at),'')::timestamptz;
  exception when others then
    v_paid_at := now();
  end;
  if v_paid_at is null then v_paid_at := now(); end if;

  v_no := 'ELY-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('public.receipt_sequence')::text, 6, '0');

  insert into public.receipts (
    receipt_no, activation_code, device_id,
    subscriber_local_id, subscriber_name, subscriber_phone, subscriber_area,
    amps, generator_name, paid_month, amount, paid_at
  ) values (
    v_no, upper(trim(p_activation_code)), trim(p_device_id),
    trim(p_subscriber_local_id), trim(p_subscriber_name), nullif(trim(p_subscriber_phone),''), nullif(trim(p_subscriber_area),''),
    greatest(coalesce(p_amps,0),0), coalesce(nullif(trim(p_generator_name),''),'مولدتي'), trim(p_paid_month), p_amount, v_paid_at
  )
  returning * into v_receipt;

  return jsonb_build_object(
    'ok', true,
    'existing', false,
    'receipt_id', v_receipt.id,
    'receipt_no', v_receipt.receipt_no,
    'subscriber_name', v_receipt.subscriber_name,
    'subscriber_phone', v_receipt.subscriber_phone,
    'subscriber_area', v_receipt.subscriber_area,
    'amps', v_receipt.amps,
    'generator_name', v_receipt.generator_name,
    'paid_month', v_receipt.paid_month,
    'amount', v_receipt.amount,
    'paid_at', v_receipt.paid_at,
    'status', v_receipt.status,
    'verify_url', v_base_url || v_receipt.verify_token::text
  );
end;
$$;

revoke all on function public.issue_receipt(text,text,text,text,text,text,integer,text,text,numeric,text) from public;
grant execute on function public.issue_receipt(text,text,text,text,text,text,integer,text,text,numeric,text) to anon, authenticated;

create or replace function public.cancel_receipt(
  p_activation_code text,
  p_device_id text,
  p_receipt_no text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_client public.clients%rowtype;
  v_receipt public.receipts%rowtype;
begin
  select * into v_client
  from public.clients
  where upper(activation_code) = upper(trim(p_activation_code))
  limit 1;

  if not found or v_client.suspended or v_client.expires_at < now() then
    return jsonb_build_object('ok', false, 'error', 'license_not_active');
  end if;

  if v_client.bound_device_id is null or v_client.bound_device_id <> trim(p_device_id) then
    return jsonb_build_object('ok', false, 'error', 'device_mismatch');
  end if;

  select * into v_receipt
  from public.receipts
  where receipt_no = trim(p_receipt_no)
    and upper(activation_code) = upper(trim(p_activation_code))
  limit 1;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'receipt_not_found');
  end if;

  if v_receipt.status = 'cancelled' then
    return jsonb_build_object('ok', true, 'already_cancelled', true, 'receipt_no', v_receipt.receipt_no);
  end if;

  update public.receipts
  set status = 'cancelled', cancelled_at = now()
  where id = v_receipt.id;

  return jsonb_build_object('ok', true, 'receipt_no', v_receipt.receipt_no, 'status', 'cancelled');
end;
$$;

revoke all on function public.cancel_receipt(text,text,text) from public;
grant execute on function public.cancel_receipt(text,text,text) to anon, authenticated;

-- Storage bucket used by the WhatsApp Edge Function.
insert into storage.buckets (id, name, public)
values ('receipt-images', 'receipt-images', false)
on conflict (id) do update set public = false;
