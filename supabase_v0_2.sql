-- Mawldati v0.2
-- تحديث دالة فحص الترخيص لإرجاع بيانات العرض للتطبيق.

create or replace function public.check_license(
  p_activation_code text,
  p_device_id text,
  p_app_version text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_client public.clients%rowtype;
  v_status text;
  v_grace integer := 3;
begin
  if p_activation_code is null or length(trim(p_activation_code)) < 6 then
    return jsonb_build_object('ok', false, 'status', 'invalid_code');
  end if;

  if p_device_id is null or length(trim(p_device_id)) < 4 then
    return jsonb_build_object('ok', false, 'status', 'invalid_device');
  end if;

  select *
    into v_client
  from public.clients
  where upper(activation_code) = upper(trim(p_activation_code))
  limit 1;

  if not found then
    return jsonb_build_object('ok', false, 'status', 'not_found');
  end if;

  select offline_grace_days
    into v_grace
  from public.app_settings
  where id = 1;

  if v_client.suspended then
    return jsonb_build_object(
      'ok', false,
      'status', 'suspended',
      'activated_at', v_client.activated_at,
      'expires_at', v_client.expires_at,
      'owner_name', v_client.owner_name,
      'generator_name', v_client.generator_name,
      'offline_grace_days', coalesce(v_grace, 3)
    );
  end if;

  if v_client.expires_at < now() then
    return jsonb_build_object(
      'ok', false,
      'status', 'expired',
      'activated_at', v_client.activated_at,
      'expires_at', v_client.expires_at,
      'owner_name', v_client.owner_name,
      'generator_name', v_client.generator_name,
      'offline_grace_days', coalesce(v_grace, 3)
    );
  end if;

  if v_client.bound_device_id is null then
    update public.clients
       set bound_device_id = trim(p_device_id),
           last_seen_at = now(),
           app_version = p_app_version
     where id = v_client.id;
  elsif v_client.bound_device_id <> trim(p_device_id) then
    return jsonb_build_object(
      'ok', false,
      'status', 'device_mismatch',
      'expires_at', v_client.expires_at,
      'offline_grace_days', coalesce(v_grace, 3)
    );
  else
    update public.clients
       set last_seen_at = now(),
           app_version = coalesce(p_app_version, app_version)
     where id = v_client.id;
  end if;

  v_status := case when v_client.plan = 'trial' then 'trial' else 'active' end;

  return jsonb_build_object(
    'ok', true,
    'status', v_status,
    'owner_name', v_client.owner_name,
    'generator_name', v_client.generator_name,
    'activated_at', v_client.activated_at,
    'expires_at', v_client.expires_at,
    'offline_grace_days', coalesce(v_grace, 3)
  );
end;
$$;

revoke all on function public.check_license(text, text, text) from public;
grant execute on function public.check_license(text, text, text) to anon, authenticated;
