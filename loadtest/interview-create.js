// 面试创建接口压测（k6）
//
// 压测目标：POST /api/interview/sessions，覆盖 Skill 出题链路（同步生成题目）。
// 这是一条 CPU + LLM 混合的写接口，和 RAG 问答互补，用来观察出题在并发下的延迟与吞吐。
//
// 运行：
//   k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=xxx -e SKILL_ID=java-backend loadtest/interview-create.js
//   k6 run -e TOKEN=xxx -e VUS=10 -e DURATION=1m -e SKILL_ID=java-backend loadtest/interview-create.js
//
// 关注指标：
//   - http_req_duration         P95 / P99
//   - interview_create_fail     业务失败率
//
// 注意：接口有 @RateLimit（GLOBAL=5 / IP=5）。压系统极限时临时放宽；压真实表现时保留并观察限流占比。
// forceCreate=true 避免命中“已有未完成会话”的去重逻辑，让每次请求都真正走出题。

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const SKILL_ID = __ENV.SKILL_ID || 'java-backend';
const QUESTION_COUNT = parseInt(__ENV.QUESTION_COUNT || '3', 10);

const failRate = new Rate('interview_create_fail');
const bizLatency = new Trend('interview_create_biz_latency', true);

export const options = {
  scenarios: {
    interview_create: {
      executor: __ENV.VUS ? 'constant-vus' : 'ramping-vus',
      ...(__ENV.VUS
        ? { vus: parseInt(__ENV.VUS, 10), duration: __ENV.DURATION || '1m' }
        : {
            startVUs: 1,
            stages: [
              { duration: '30s', target: 3 },
              { duration: '1m', target: 8 },
              { duration: '30s', target: 0 },
            ],
          }),
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<20000', 'p(99)<30000'],
    interview_create_fail: ['rate<0.2'],
  },
};

export default function () {
  const payload = JSON.stringify({
    questionCount: QUESTION_COUNT,
    forceCreate: true,
    skillId: SKILL_ID,
    difficulty: 'mid',
  });
  const headers = { 'Content-Type': 'application/json' };
  if (TOKEN) {
    headers.Authorization = `Bearer ${TOKEN}`;
  }
  const params = { headers };

  const res = http.post(`${BASE_URL}/api/interview/sessions`, payload, params);
  bizLatency.add(res.timings.duration);

  let ok = res.status === 200;
  if (ok) {
    try {
      const body = res.json();
      ok = body && (body.code === 0 || body.code === 200);
    } catch (e) {
      ok = false;
    }
  }
  failRate.add(!ok);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'business ok': () => ok,
  });
}
