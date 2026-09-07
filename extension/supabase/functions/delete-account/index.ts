// 계정 삭제 Edge Function.
//
// 구글 플레이 정책상 앱 안에서 계정을 지울 수 있는 경로가 있어야 하는데, anon key로는
// auth.users 행을 지울 수 없다(RLS는 public 스키마 테이블만 지켜주고 auth 스키마는 아예
// 손댈 수 없음). 그래서 service_role 키로 도는 이 함수가 필요하다 — 키는 서버에만 있고
// 클라이언트(확장/안드로이드)에는 절대 들어가지 않는다.
//
// public 테이블은 손대지 않는다: daily_usage / settings / streaks / achievements 네 개 모두
// `references auth.users(id) on delete cascade`라(schema.sql 참고) auth 유저를 지우면 DB가
// 알아서 같이 지운다. 여기서 테이블별 delete를 또 쓰면 스키마와 중복된 규칙이 두 군데로
// 갈라져서, 나중에 테이블이 하나 늘 때 이 파일을 같이 안 고치면 조용히 찌꺼기가 남는다.
// 테이블을 추가할 때 cascade만 제대로 걸면 이 함수는 건드릴 필요가 없다.

import { createClient } from 'npm:@supabase/supabase-js@2';

// 확장은 chrome-extension:// 오리진에서 fetch하므로 프리플라이트가 날아온다. 오리진이
// 확장 ID마다 다르고(개발용 unpacked ID까지) 이 함수는 JWT로 스스로를 지키므로 * 로 연다.
// 안드로이드는 네이티브 HTTP라 CORS와 무관하지만 같은 핸들러가 양쪽을 다 받는다.
const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

/** 응답은 성공/실패 모두 같은 모양의 JSON — 클라이언트가 분기하기 쉽게. */
function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

Deno.serve(async (req: Request): Promise<Response> => {
  // 프리플라이트는 본문 없이 헤더만 돌려주면 된다.
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  // 계정 삭제는 되돌릴 수 없으므로 GET 같은 걸로 실수로(혹은 링크 프리페치로) 불려선 안 된다.
  if (req.method !== 'POST') {
    return json({ success: false, error: 'method_not_allowed' }, 405);
  }

  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const anonKey = Deno.env.get('SUPABASE_ANON_KEY');
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

  // 셋 다 Supabase가 배포된 함수에 자동으로 주입한다. 없다면 설정이 잘못된 것 —
  // 키 값 자체는 절대 로그에 찍지 않는다(어느 게 비었는지만 알면 충분하다).
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    console.error('delete-account: 환경변수 누락', {
      hasUrl: Boolean(supabaseUrl),
      hasAnonKey: Boolean(anonKey),
      hasServiceRoleKey: Boolean(serviceRoleKey),
    });
    return json({ success: false, error: 'server_misconfigured' }, 500);
  }

  // ── 보안 경계 ──────────────────────────────────────────────────────────────
  // 지울 대상 id는 오직 여기서만 나온다. 본문에 담긴 user_id 같은 건 읽지도 않는다 —
  // 읽는 순간 "남의 id를 넣어서 남의 계정을 지우는" 구멍이 되기 때문이다.
  // Authorization 헤더의 access token을 anon 클라이언트에 그대로 얹고 getUser()를 부르면
  // Auth 서버가 토큰 서명과 만료를 검증한 뒤 그 토큰의 주인을 돌려준다. 즉 호출자는
  // 자기가 가진 토큰의 주인, 곧 자기 자신 외에는 어떤 id도 만들어낼 수 없다.
  const authHeader = req.headers.get('Authorization');
  if (!authHeader) {
    return json({ success: false, error: 'unauthorized' }, 401);
  }

  const callerClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data: userData, error: userError } = await callerClient.auth.getUser();
  const user = userData?.user;
  if (userError || !user) {
    // 토큰이 없거나/만료됐거나/위조된 경우. 왜 실패했는지는 클라이언트에 알려주지 않는다.
    console.error('delete-account: 호출자 인증 실패', userError?.message ?? 'user 없음');
    return json({ success: false, error: 'unauthorized' }, 401);
  }
  // ──────────────────────────────────────────────────────────────────────────

  // 여기부터가 권한 상승 구간. service_role 클라이언트는 RLS를 전부 무시하므로
  // 위에서 확인한 user.id 말고 다른 값이 흘러들어오지 않게 하는 게 이 함수의 전부다.
  const adminClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { error: deleteError } = await adminClient.auth.admin.deleteUser(user.id);

  if (deleteError) {
    // 원문 메시지에는 내부 구조(테이블명, 제약조건명 등)가 섞여 나올 수 있으므로
    // 서버 로그에만 남기고 클라이언트에는 일반적인 코드만 준다.
    console.error('delete-account: 삭제 실패', user.id, deleteError.message);
    return json({ success: false, error: 'delete_failed' }, 500);
  }

  console.log('delete-account: 삭제 완료', user.id);

  // 주의: 유저를 지워도 이미 발급된 access token은 만료 전까지 형식상 유효하다.
  // 다만 그 토큰으로 접근할 행이 cascade로 전부 사라진 뒤라 할 수 있는 일이 없다.
  // 그래도 클라이언트는 이 응답을 받으면 즉시 로컬 세션을 지우고 로그아웃해야 한다.
  return json({ success: true }, 200);
});
