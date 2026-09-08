import { supabase } from '../lib/supabaseClient.js';
import { applyI18n, t } from '../lib/i18n.js';

applyI18n();

const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const submitButton = document.getElementById('submitButton');
const switchModeLink = document.getElementById('switchModeLink');
const errorBox = document.getElementById('authError');

let mode = 'signIn';

function render() {
  submitButton.textContent = mode === 'signIn' ? t('auth_sign_in') : t('auth_sign_up');
  switchModeLink.textContent =
    mode === 'signIn' ? t('auth_switch_to_sign_up') : t('auth_switch_to_sign_in');
}

switchModeLink.addEventListener('click', () => {
  mode = mode === 'signIn' ? 'signUp' : 'signIn';
  errorBox.textContent = '';
  render();
});

// 완료 화면은 문구 한 줄뿐이라 마크업을 만들어 끼운다. innerHTML 대신 DOM으로 짓는 이유는
// 번역 문구가 마크업으로 해석될 여지를 아예 없애기 위해서다.
function showDoneMessage(message) {
  const box = document.createElement('div');
  box.className = 'auth-box';
  const paragraph = document.createElement('p');
  paragraph.textContent = message;
  box.appendChild(paragraph);
  document.body.replaceChildren(box);
}

submitButton.addEventListener('click', async () => {
  errorBox.textContent = '';
  const email = emailInput.value.trim();
  const password = passwordInput.value;
  if (!email || password.length < 6) {
    errorBox.textContent = t('auth_error_invalid_input');
    return;
  }

  submitButton.disabled = true;
  try {
    if (mode === 'signIn') {
      const { error } = await supabase.auth.signInWithPassword({ email, password });
      if (error) throw error;
      showDoneMessage(t('auth_signed_in_done'));
    } else {
      const { error } = await supabase.auth.signUp({ email, password });
      if (error) throw error;
      showDoneMessage(t('auth_signed_up_done'));
    }
  } catch (e) {
    errorBox.textContent = e.message || t('auth_error_generic');
  } finally {
    submitButton.disabled = false;
  }
});

render();
