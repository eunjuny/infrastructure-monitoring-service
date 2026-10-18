import { chromium } from '@playwright/test'
import { mkdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const source = path.join(root, 'docs/performance/2026-10-18-final-submission.json')
const destination = path.join(root, 'docs/images/performance-evidence')
const report = JSON.parse(await readFile(source, 'utf8'))
const { environment, measurementsMs: values, scroll, separateObservations } = report

if (report.validation.verifiedIterations !== 20 || report.validation.apiHttpErrors !== 0 || report.validation.measurementErrors !== 0) {
  throw new Error('Evidence images require a complete 20-iteration run without API or measurement errors.')
}
for (const [name, item] of Object.entries(values)) {
  if (item.target !== null && item.overTarget !== 0) throw new Error(`Cannot publish a passing evidence image for ${name}.`)
}
if (scroll.targetFps !== 55 || scroll.minOneSecondRafFps < scroll.targetFps) {
  throw new Error('Scroll evidence does not meet the one-second minimum frame-rate check.')
}

const metric = (name, label, explanation) => ({ name, label, explanation, ...values[name] })
const panels = [
  {
    file: '01-first-screen.png', title: '첫 화면 진입', subtitle: '페이지 이동부터 Cluster 첫 행이 조작 가능한 시점까지',
    metrics: [
      metric('ssoNavigation', '기존 Keycloak 세션 포함', '브라우저 이동·SSO 왕복·첫 Cluster 목록 표시'),
      metric('callbackReady', '인증 콜백 후 화면 준비', '앱 문서 시작부터 첫 Cluster 목록까지 · User Timing'),
    ],
  },
  {
    file: '02-tree-interactions.png', title: '계층 펼침·접기', subtitle: '실제 행이 표시되거나 제거되고 두 번의 화면 프레임을 지난 시점',
    metrics: [
      metric('expandCold', '첫 펼침 · 네트워크 요청 포함', 'Cluster → Node 하위 목록이 화면에 반영'),
      metric('collapse', '접기', '열린 하위 목록의 DOM 제거를 확인'),
      metric('expandWarm', '캐시된 목록 다시 펼침', '이미 요청한 하위 목록의 재표시'),
    ],
  },
  {
    file: '03-search.png', title: '이름 검색', subtitle: '검색 요청 시작부터 일치하는 결과 한 건이 표시될 때까지',
    metrics: [metric('search', '검색 결과 반영', '250ms 입력 debounce는 측정 구간에서 제외'),
    ],
  },
  {
    file: '04-filter.png', title: '상태 필터', subtitle: '필터 선택부터 목록의 상태가 모두 조건과 일치할 때까지',
    metrics: [metric('filter', '오류 상태 필터 반영', '필터 변경·API 응답·화면 행 검증'),
    ],
  },
  {
    file: '05-manual-refresh.png', title: '수동 새로고침', subtitle: '버튼 입력부터 목록이 다시 유휴 상태가 될 때까지',
    metrics: [
      metric('refreshReadOnly', '변경 없는 새로고침', '읽기 전용 요청 그룹 완료'),
      metric('refreshChanged', '변경 내용 반영', 'DB 변경 후 목록·상세에 새 상태가 표시'),
    ],
  },
  {
    file: '06-auto-refresh.png', title: '자동 갱신', subtitle: '변경된 Pod가 다음 폴링 요청을 통해 목록·상세에 표시될 때까지',
    metrics: [metric('pollChanged', '변경 상태 자동 반영', '별도 3회 측정 · 장시간 안정성 시험은 아님'),
    ],
  },
]

const esc = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
const oneMetric = item => {
  const scale = item.target * 1.15
  const bar = Math.min(100, item.max / scale * 100)
  const goal = item.target / scale * 100
  const samples = item.count ?? item.samples ?? report.validation.iterations
  return `<section class="metric">
    <div class="metric-head"><div><h2>${esc(item.label)}</h2><p>${esc(item.explanation)}</p></div><span class="pass">목표 이내</span></div>
    <div class="numbers"><div><strong>${item.median.toFixed(1)}</strong><span>중앙값 ms</span></div><div><strong>${item.p95.toFixed(1)}</strong><span>P95 ms</span></div><div><strong>${item.max.toFixed(1)}</strong><span>최댓값 ms</span></div><div><strong>${esc(item.target)} ms</strong><span>기준 · 초과 0/${samples}</span></div></div>
    <div class="track"><span class="fill" style="width:${bar}%"></span><i class="goal" style="left:${goal}%"></i></div>
    <div class="legend"><span>최댓값</span><span>목표 기준</span></div>
  </section>`
}

const scrollPanel = {
  file: '07-scroll.png', title: '가상 리스트 스크롤', subtitle: '15만 Pod 데이터 · 500개 캐시 · 10초 연속 스크롤',
  custom: `<section class="metric"><div class="numbers"><div><strong>${scroll.cdpDrawFrameFps.toFixed(2)}</strong><span>CDP compositor FPS</span></div><div><strong>${scroll.rafFps.toFixed(2)}</strong><span>평균 RAF FPS</span></div><div><strong>${scroll.minOneSecondRafFps.toFixed(0)}</strong><span>최저 1초 RAF FPS</span></div><div><strong>${scroll.visibleRows}</strong><span>동시 표시 DOM 행</span></div></div><div class="fps-track"><span></span><i></i></div><div class="legend"><span>측정 최저 1초: ${scroll.minOneSecondRafFps.toFixed(0)} FPS</span><span>기준: ${scroll.targetFps} FPS</span></div><p class="footnote">18ms 초과 RAF ${scroll.framesOver18ms}회. CDP compositor·RAF 보조 계측이며 Chrome FPS 미터 직접 측정은 아닙니다.</p></section>`,
}
const memoryPanel = {
  file: '08-memory.png', title: 'Chrome 탭 메모리', subtitle: 'Chrome 작업 관리자에서 별도 시점에 직접 확인한 값',
  conditions: separateObservations.memoryConditions,
  custom: `<section class="metric"><div class="numbers memory"><div><strong>${separateObservations.chromeTaskManagerTabMemoryMb.toFixed(1)}</strong><span>탭 메모리 MB</span></div><div><strong>300</strong><span>참고 기준 MB</span></div><div><strong>별도 관측</strong><span>CPU 4× 반복 실행과 다른 측정</span></div></div><div class="fps-track memory-track"><span style="width:${Math.min(100, separateObservations.chromeTaskManagerTabMemoryMb / 300 * 100)}%"></span><i style="left:100%"></i></div><div class="legend"><span>작업 관리자 관측</span><span>참고 기준</span></div><p class="footnote">단일 시점 탭 수치이며 반복 탐색 전후의 메모리 증가량은 측정하지 않았습니다.</p></section>`,
}

await mkdir(destination, { recursive: true })
const browser = await chromium.launch({ headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 })
  for (const panel of [...panels, scrollPanel, memoryPanel]) {
    const rows = panel.custom ?? panel.metrics.map(oneMetric).join('')
    const subtitle = panel.subtitle
    const conditions = panel.conditions ?? [`Chrome ${environment.browser.replace('Chrome ','')}`, 'CPU 4× 제한', '캐시 비활성화', `화면 ${environment.viewport}`, `Pod ${environment.dataset.pods.toLocaleString()}개`, '네트워크 제한 없음']
    const sourceText = panel.file === '08-memory.png'
      ? 'Chrome 작업 관리자 별도 관측 · 반복 측정과 다른 조건'
      : panel.file === '07-scroll.png'
        ? `전체 ${report.validation.iterations}회 응답 측정의 마지막 반복에서 10초 연속 스크롤 1회 · ${scroll.durationMs.toFixed(1)}ms`
        : `${report.validation.iterations}회 반복 · 자동 갱신은 별도 ${values.pollChanged.samples}회`
    await page.setContent(`<!doctype html><html lang="ko"><meta charset="utf-8"><style>
      *{box-sizing:border-box}body{margin:0;background:#edf2f8;color:#14253a;font-family:-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR",sans-serif}
      .canvas{width:1180px;margin:36px auto;padding:42px 48px 34px;background:#fff;border:1px solid #d9e2ee;border-radius:18px;box-shadow:0 12px 34px #1e355012}
      .eyebrow{font-size:13px;letter-spacing:.12em;font-weight:700;color:#52719a}.title{font-size:34px;margin:12px 0 8px;letter-spacing:-.04em}.subtitle{font-size:16px;color:#526276;margin:0 0 30px}
      .condition{display:flex;gap:9px;flex-wrap:wrap;margin:22px 0 28px}.chip{background:#f0f5fb;border:1px solid #dbe6f2;border-radius:999px;padding:8px 12px;font-size:13px;color:#344d6b}
      .metric{padding:19px 0 17px;border-top:1px solid #e6edf5}.metric-head{display:flex;justify-content:space-between;align-items:flex-start;gap:18px}.metric h2{margin:0;font-size:18px}.metric p{margin:5px 0 0;color:#65758a;font-size:13px}.pass{color:#176b4b;background:#e8f7ef;border:1px solid #c5ead5;border-radius:999px;padding:6px 10px;font-size:12px;font-weight:700;white-space:nowrap}
      .numbers{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:20px 0 12px}.numbers>div{background:#f7f9fc;border:1px solid #e5ebf3;border-radius:11px;padding:13px 14px}.numbers strong{display:block;font-size:23px;letter-spacing:-.03em}.numbers span{display:block;margin-top:4px;font-size:12px;color:#68798e}.track,.fps-track{position:relative;height:9px;background:#e9eef5;border-radius:999px;overflow:visible;margin:15px 2px 2px}.fill{display:block;height:100%;background:linear-gradient(90deg,#5089de,#2d68c4);border-radius:999px}.goal,.fps-track i{position:absolute;top:-5px;height:19px;border-left:2px dashed #d17735}.legend{display:flex;justify-content:space-between;color:#718096;font-size:11px;margin-top:7px}.fps-track span{display:block;width:100%;height:100%;background:#37a875;border-radius:999px}.fps-track i{left:91.7%}.footnote{font-size:12px;color:#68798e;line-height:1.55;margin-top:19px}.memory{grid-template-columns:1.1fr 1.1fr 2fr}.memory-track span{background:linear-gradient(90deg,#54a27e,#388763)}
      .source{border-top:1px solid #e5ebf3;margin-top:23px;padding-top:14px;display:flex;justify-content:space-between;gap:12px;color:#708097;font-size:11px}.source strong{color:#415977}
    </style><body><main class="canvas"><div class="eyebrow">INFRASTRUCTURE MONITORING SERVICE · PERFORMANCE EVIDENCE</div><h1 class="title">${esc(panel.title)}</h1><p class="subtitle">${esc(subtitle)}</p><div class="condition">${conditions.map(value => `<span class="chip">${esc(value)}</span>`).join('')}</div>${rows}<footer class="source"><span><strong>${panel.file === '08-memory.png' ? '출처' : '측정 실행'}:</strong> ${esc(panel.file === '08-memory.png' ? sourceText : `${report.run} · ${sourceText}`)}</span><span>${panel.file === '08-memory.png' ? '반복 측정과 조건이 다릅니다.' : '요약 이미지 · DevTools 원본 캡처 아님'}</span></footer></main></body></html>`, { waitUntil: 'load' })
    await page.locator('.canvas').screenshot({ path: path.join(destination, panel.file), animations: 'disabled' })
  }
} finally {
  await browser.close()
}
process.stdout.write(`Generated ${panels.length + 2} goal-specific performance evidence images from ${path.relative(root, source)}\n`)
