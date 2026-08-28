import { supabase } from '../lib/supabaseClient.js';

const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const submitButton = document.getElementById('submitButton');
const switchModeLink = document.getElementById('switchModeLink');
const errorBox = document.getElementById('authError');

let mode = 'signIn';

function render() {
  submitButton.textContent = mode === 'signIn' ? '로그인' : '회원가입';
  switchModeLink.textContent = mode === 'signIn' ? '계정이 없으신가요? 회원가입' : '이미 계정이 있으신가요? 로그인';
}

switchModeLink.addEventListener('click', () => {
  mode = mode === 'signIn' ? 'signUp' : 'signIn';
  errorBox.textContent = '';
  render();
});

submitButton.addEventListener('click', async () => {
  errorBox.textContent = '';
  const email = emailInput.value.trim();
  const password = passwordInput.value;
  if (!email || password.length < 6) {
    errorBox.textContent = '이메일과 6자 이상 비밀번호를 입력하세요.';
    return;
  }

  submitButton.disabled = true;
  try {
    if (mode === 'signIn') {
      const { error } = await supabase.auth.signInWithPassword({ email, password });
      if (error) throw error;
      document.body.innerHTML = '<div class="auth-box"><p>로그인 완료. 이 탭을 닫고 TubeLimiter 팝업을 다시 열어주세요.</p></div>';
    } else {
      const { error } = await supabase.auth.signUp({ email, password });
      if (error) throw error;
      document.body.innerHTML = '<div class="auth-box"><p>가입 확인 메일을 보냈어요. 메일함을 확인한 뒤 이 탭을 닫고 로그인해주세요. (Supabase 프로젝트에서 이메일 확인을 껐다면 바로 로그인해도 됩니다.)</p></div>';
    }
  } catch (e) {
    errorBox.textContent = e.message || '요청 중 오류가 발생했습니다.';
  } finally {
    submitButton.disabled = false;
  }
});

render();
