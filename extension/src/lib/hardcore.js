// 하드코어 모드를 끄면 충동적으로 다시 켰다 껐다 할 수 있으므로,
// 끄기는 요청 시각만 기록해두고 이 시간이 지난 뒤에야 실제로 꺼지게 한다.
export const HARDCORE_DISABLE_COOLDOWN_MS = 60 * 60 * 1000;
