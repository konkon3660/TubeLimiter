// 네비게이션 기능
document.addEventListener('DOMContentLoaded', function() {
  const navLinks = document.querySelectorAll('.nav-item a');
  const contentSections = document.querySelectorAll('.content-section');
  
  navLinks.forEach(link => {
    link.addEventListener('click', function(e) {
      e.preventDefault();
      
      // 모든 네비게이션 링크에서 active 클래스 제거
      navLinks.forEach(l => l.classList.remove('active'));
      // 클릭된 링크에 active 클래스 추가
      this.classList.add('active');
      
      // 모든 콘텐츠 섹션 숨기기
      contentSections.forEach(section => section.classList.remove('active'));
      
      // 해당하는 섹션 표시
      const targetId = this.getAttribute('href').substring(1);
      const targetSection = document.getElementById(targetId);
      if (targetSection) {
        targetSection.classList.add('active');
      }
    });
  });
  
  // 현재 날짜 표시 예시
  const currentDate = new Date().toLocaleDateString('ko-KR');
  const currentDateElement = document.getElementById('currentDate');
  if (currentDateElement) {
    currentDateElement.textContent = currentDate;
  }
  
  
});