import { getStorage } from '/utils/storage.js';
import { getTodayDate } from '/utils/time.js';

let usageChart = null;
let currentStartDate = null;
let currentEndDate = null;
let isAutoYAxis = true;
let manualYAxisMax = null;
let currentAggregationType = 'daily';

// 시간 포맷팅 함수 (분 -> 시간/분)
function formatMinutesToHoursMinutes(totalMinutes) {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) {
    return `${hours}시간 ${minutes}분`;
  } else {
    return `${minutes}분`;
  }
}

// 데이터 타입별 레이블 매핑
const dataTypeLabels = {
  total: '전체 사용 시간',
  shorts: 'Shorts 사용 시간',
  study: '공부 시간',
  play: '놀이 시간'
};

// 데이터 타입별 색상 매핑
const dataTypeColors = {
  total: { border: 'rgba(54, 162, 235, 1)', background: 'rgba(54, 162, 235, 0.2)' },
  shorts: { border: 'rgba(255, 99, 132, 1)', background: 'rgba(255, 99, 132, 0.2)' },
  study: { border: 'rgba(75, 192, 192, 1)', background: 'rgba(75, 192, 192, 0.2)' },
  play: { border: 'rgba(255, 159, 64, 1)', background: 'rgba(255, 159, 64, 0.2)' }
};

async function updateSummaryStats() {
  const result = await getStorage(['usage_history']);
  const usageHistory = result.usage_history || {};

  let totalUsageMinutes = 0;
  let daysWithUsage = 0;

  for (const date in usageHistory) {
    const usageMs = usageHistory[date];
    const usageMinutes = Math.floor(usageMs / (1000 * 60));
    totalUsageMinutes += usageMinutes;
    daysWithUsage++;
  }

  const averageDailyUsageMinutes = daysWithUsage > 0 ? Math.floor(totalUsageMinutes / daysWithUsage) : 0;

  document.getElementById('totalUsage').textContent = formatMinutesToHoursMinutes(totalUsageMinutes);
  document.getElementById('averageDailyUsage').textContent = formatMinutesToHoursMinutes(averageDailyUsageMinutes);
}

// 특정 타입의 사용 기록 가져오기
async function getUsageHistoryByType(type) {
  if (type === 'total') {
    const result = await getStorage(['usage_history']);
    return result.usage_history || {};
  } else {
    const storageKey = `usage_history_${type}`;
    const result = await getStorage([storageKey]);
    return result[storageKey] || {};
  }
}

// 날짜 범위 생성 함수
function generateDateRange(startDate, endDate) {
  const dates = [];
  const currentDate = new Date(startDate);
  const end = new Date(endDate);

  while (currentDate <= end) {
    const dateString = `${currentDate.getFullYear()}-${String(currentDate.getMonth() + 1).padStart(2, '0')}-${String(currentDate.getDate()).padStart(2, '0')}`;
    dates.push(dateString);
    currentDate.setDate(currentDate.getDate() + 1);
  }

  return dates;
}

// 날짜 범위로부터 시작일과 종료일 계산 (지난 N일)
function getDateRangeFromDays(days) {
  const endDate = new Date();
  const startDate = new Date();
  startDate.setDate(endDate.getDate() - (days - 1));
  return { startDate, endDate };
}

// 주차 시작일 계산 (월요일 기준)
function getWeekStart(date) {
  const d = new Date(date);
  const day = d.getDay();
  const diff = d.getDate() - day + (day === 0 ? -6 : 1); // 월요일 기준
  return new Date(d.setDate(diff));
}

// 월 시작일 계산
function getMonthStart(date) {
  const d = new Date(date);
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

// 날짜를 YYYY-MM-DD 형식으로 변환
function formatDateString(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

// 주별로 데이터 집계
function aggregateByWeek(usageHistory, startDate, endDate) {
  const weeklyData = {};
  const dates = generateDateRange(startDate, endDate);

  dates.forEach(dateString => {
    const date = new Date(dateString);
    const weekStart = getWeekStart(date);
    const weekKey = formatDateString(weekStart);

    if (!weeklyData[weekKey]) {
      weeklyData[weekKey] = 0;
    }
    weeklyData[weekKey] += (usageHistory[dateString] || 0);
  });

  return weeklyData;
}

// 월별로 데이터 집계
function aggregateByMonth(usageHistory, startDate, endDate) {
  const monthlyData = {};
  const dates = generateDateRange(startDate, endDate);

  dates.forEach(dateString => {
    const date = new Date(dateString);
    const monthKey = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;

    if (!monthlyData[monthKey]) {
      monthlyData[monthKey] = 0;
    }
    monthlyData[monthKey] += (usageHistory[dateString] || 0);
  });

  return monthlyData;
}

// 주별 레이블 생성
function generateWeeklyLabels(startDate, endDate) {
  const labels = [];
  const weekStarts = new Set();
  const dates = generateDateRange(startDate, endDate);

  dates.forEach(dateString => {
    const date = new Date(dateString);
    const weekStart = getWeekStart(date);
    const weekKey = formatDateString(weekStart);
    weekStarts.add(weekKey);
  });

  return Array.from(weekStarts).sort();
}

// 월별 레이블 생성
function generateMonthlyLabels(startDate, endDate) {
  const labels = [];
  const months = new Set();
  const dates = generateDateRange(startDate, endDate);

  dates.forEach(dateString => {
    const date = new Date(dateString);
    const monthKey = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    months.add(monthKey);
  });

  return Array.from(months).sort();
}

async function drawUsageChart(startDate = null, endDate = null, mainType = 'total', subType = 'none', aggregationType = 'daily') {
  const ctx = document.getElementById('usageChart').getContext('2d');

  // 날짜 범위가 지정되지 않은 경우 현재 설정된 범위 사용
  if (!startDate || !endDate) {
    if (currentStartDate && currentEndDate) {
      startDate = currentStartDate;
      endDate = currentEndDate;
    } else {
      // 기본값: 지난 7일
      const range = getDateRangeFromDays(7);
      startDate = range.startDate;
      endDate = range.endDate;
    }
  }

  // 현재 날짜 범위 저장
  currentStartDate = startDate;
  currentEndDate = endDate;
  currentAggregationType = aggregationType;

  // 집계 타입에 따라 레이블과 데이터 생성
  let labels;
  let mainAggregatedData;
  let subAggregatedData;

  const mainUsageHistory = await getUsageHistoryByType(mainType);
  const subUsageHistory = subType !== 'none' ? await getUsageHistoryByType(subType) : null;

  if (aggregationType === 'weekly') {
    labels = generateWeeklyLabels(startDate, endDate);
    mainAggregatedData = aggregateByWeek(mainUsageHistory, startDate, endDate);
    subAggregatedData = subUsageHistory ? aggregateByWeek(subUsageHistory, startDate, endDate) : null;
  } else if (aggregationType === 'monthly') {
    labels = generateMonthlyLabels(startDate, endDate);
    mainAggregatedData = aggregateByMonth(mainUsageHistory, startDate, endDate);
    subAggregatedData = subUsageHistory ? aggregateByMonth(subUsageHistory, startDate, endDate) : null;
  } else {
    // 일별 (기본)
    labels = generateDateRange(startDate, endDate);
    mainAggregatedData = mainUsageHistory;
    subAggregatedData = subUsageHistory;
  }

  // 데이터셋 준비
  const datasets = [];

  // 주 그래프 데이터
  const mainData = labels.map(label => Math.floor((mainAggregatedData[label] || 0) / (1000 * 60)));

  datasets.push({
    label: dataTypeLabels[mainType],
    data: mainData,
    borderColor: dataTypeColors[mainType].border,
    backgroundColor: dataTypeColors[mainType].background,
    borderWidth: 2,
    fill: false,
    tension: 0.1
  });

  // 부 그래프 데이터 (선택된 경우)
  if (subType !== 'none' && subAggregatedData) {
    const subData = labels.map(label => Math.floor((subAggregatedData[label] || 0) / (1000 * 60)));

    datasets.push({
      label: dataTypeLabels[subType],
      data: subData,
      borderColor: dataTypeColors[subType].border,
      backgroundColor: dataTypeColors[subType].background,
      borderWidth: 2,
      fill: false,
      tension: 0.1,
      borderDash: [5, 5] // 점선으로 표시
    });
  }

  // 데이터의 최대값을 찾아 Y축 max 설정
  const allData = datasets.flatMap(ds => ds.data);
  const maxUsage = Math.max(...allData);
  let yAxisMax;

  if (isAutoYAxis) {
    yAxisMax = maxUsage > 0 ? Math.ceil(maxUsage / 10) * 10 + 5 : 30;
  } else {
    yAxisMax = manualYAxisMax || 30;
  }

  if (usageChart) {
    usageChart.destroy();
  }

  usageChart = new window.Chart(ctx, {
    type: 'line',
    data: {
      labels: labels,
      datasets: datasets
    },
    options: {
      animation: {
        duration: 750,
        easing: 'easeInOutQuart'
      },
      scales: {
        y: {
          beginAtZero: true,
          title: {
            display: true,
            text: '시간 (분)',
            font: {
              weight: 'bold',
              size: 13
            }
          },
          max: yAxisMax,
          grid: {
            color: 'rgba(0, 0, 0, 0.05)'
          }
        },
        x: {
          title: {
            display: true,
            text: '날짜',
            font: {
              weight: 'bold',
              size: 13
            }
          },
          grid: {
            display: false
          }
        }
      },
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        tooltip: {
          backgroundColor: 'rgba(0, 0, 0, 0.8)',
          padding: 12,
          titleFont: {
            size: 14,
            weight: 'bold'
          },
          bodyFont: {
            size: 13
          },
          cornerRadius: 8,
          callbacks: {
            label: function(context) {
              let label = context.dataset.label || '';
              if (label) {
                label += ': ';
              }
              if (context.parsed.y !== null) {
                label += formatMinutesToHoursMinutes(context.parsed.y);
              }
              return label;
            }
          }
        },
        legend: {
          display: true,
          position: 'top',
          labels: {
            padding: 15,
            font: {
              size: 12,
              weight: 'bold'
            },
            usePointStyle: true,
            pointStyle: 'circle'
          }
        }
      },
      interaction: {
        intersect: false,
        mode: 'index'
      }
    }
  });
}

// 차트 업데이트 함수
function updateChart() {
  const mainType = document.getElementById('mainDataType').value;
  const subType = document.getElementById('subDataType').value;
  const aggregationType = document.getElementById('aggregationType').value;
  drawUsageChart(currentStartDate, currentEndDate, mainType, subType, aggregationType);
}

document.addEventListener('DOMContentLoaded', async () => {
  const result = await chrome.storage.local.get('darkMode');
  if (result.darkMode) {
    document.body.classList.add('dark-mode');
  }

  // 날짜 입력 필드 초기화 (지난 7일)
  const range = getDateRangeFromDays(7);
  const startDateInput = document.getElementById('startDate');
  const endDateInput = document.getElementById('endDate');
  const autoYAxisCheckbox = document.getElementById('autoYAxis');
  const yAxisMaxInput = document.getElementById('yAxisMax');

  startDateInput.valueAsDate = range.startDate;
  endDateInput.valueAsDate = range.endDate;
  currentStartDate = range.startDate;
  currentEndDate = range.endDate;

  updateSummaryStats();
  drawUsageChart(range.startDate, range.endDate, 'total', 'none');

  // 버튼 active 상태 토글 함수
  function setActiveButton(buttonId) {
    document.querySelectorAll('.button-group button').forEach(btn => {
      btn.classList.remove('active');
    });
    document.getElementById(buttonId).classList.add('active');
  }

  // 지난 N일 버튼
  document.getElementById('showLast7Days').addEventListener('click', () => {
    const range = getDateRangeFromDays(7);
    startDateInput.valueAsDate = range.startDate;
    endDateInput.valueAsDate = range.endDate;
    currentStartDate = range.startDate;
    currentEndDate = range.endDate;
    document.getElementById('aggregationType').value = 'daily';
    setActiveButton('showLast7Days');
    updateChart();
  });

  document.getElementById('showLast30Days').addEventListener('click', () => {
    const range = getDateRangeFromDays(30);
    startDateInput.valueAsDate = range.startDate;
    endDateInput.valueAsDate = range.endDate;
    currentStartDate = range.startDate;
    currentEndDate = range.endDate;
    document.getElementById('aggregationType').value = 'daily';
    setActiveButton('showLast30Days');
    updateChart();
  });

  // 지난 12주 버튼
  document.getElementById('showLast12Weeks').addEventListener('click', () => {
    const range = getDateRangeFromDays(84); // 12주 = 84일
    startDateInput.valueAsDate = range.startDate;
    endDateInput.valueAsDate = range.endDate;
    currentStartDate = range.startDate;
    currentEndDate = range.endDate;
    document.getElementById('aggregationType').value = 'weekly';
    setActiveButton('showLast12Weeks');
    updateChart();
  });

  // 지난 12개월 버튼
  document.getElementById('showLast12Months').addEventListener('click', () => {
    const endDate = new Date();
    const startDate = new Date();
    startDate.setMonth(endDate.getMonth() - 11);
    startDate.setDate(1); // 월 시작일로 설정

    startDateInput.valueAsDate = startDate;
    endDateInput.valueAsDate = endDate;
    currentStartDate = startDate;
    currentEndDate = endDate;
    document.getElementById('aggregationType').value = 'monthly';
    setActiveButton('showLast12Months');
    updateChart();
  });

  // 날짜 범위 적용 버튼
  document.getElementById('applyDateRange').addEventListener('click', () => {
    const startDate = startDateInput.valueAsDate;
    const endDate = endDateInput.valueAsDate;

    if (!startDate || !endDate) {
      alert('시작일과 종료일을 모두 선택해주세요.');
      return;
    }

    if (startDate > endDate) {
      alert('시작일은 종료일보다 이전이어야 합니다.');
      return;
    }

    currentStartDate = startDate;
    currentEndDate = endDate;
    // 날짜 범위 직접 선택 시 버튼 active 상태 제거
    document.querySelectorAll('.button-group button').forEach(btn => {
      btn.classList.remove('active');
    });
    updateChart();
  });

  // Y축 자동/수동 전환
  autoYAxisCheckbox.addEventListener('change', (e) => {
    isAutoYAxis = e.target.checked;
    yAxisMaxInput.disabled = isAutoYAxis;

    if (!isAutoYAxis && yAxisMaxInput.value) {
      manualYAxisMax = parseInt(yAxisMaxInput.value);
    }

    updateChart();
  });

  // Y축 최대값 수동 입력
  yAxisMaxInput.addEventListener('input', (e) => {
    if (!isAutoYAxis) {
      const value = parseInt(e.target.value);
      if (value && value >= 10) {
        manualYAxisMax = value;
        updateChart();
      }
    }
  });

  // 데이터 타입 선택 변경 시
  document.getElementById('mainDataType').addEventListener('change', updateChart);
  document.getElementById('subDataType').addEventListener('change', updateChart);
  document.getElementById('aggregationType').addEventListener('change', updateChart);
});