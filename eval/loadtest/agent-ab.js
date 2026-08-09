// Multi-Agent 出题 A/B 压测（k6）
//
// 目的：用同一负载对比 Critic/Reflexion 开启与关闭时的端到端延迟、失败率。
// 后端开关不在脚本里切，分别重启后端：
//   APP_AI_AGENT_CRITIC_ENABLED=true  mvn -pl backend spring-boot:run
//   APP_AI_AGENT_CRITIC_ENABLED=false mvn -pl backend spring-boot:run
//
// 运行：
//   k6 run -e BASE_URL=http://localhost:8082 -e TOKEN=xxx -e SKILL_ID=java-backend eval/loadtest/agent-ab.js
//   k6 run -e TOKEN=xxx -e VUS=5 -e DURATION=2m -e QUESTION_COUNT=3 eval/loadtest/agent-ab.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL, authHeaders, parseIds, resolveToken } from './helpers.js';

const SKILL_ID = __ENV.SKILL_ID || 'java-backend';
const QUESTION_COUNT = parseInt(__ENV.QUESTION_COUNT || '3', 10);
const KB_IDS = parseIds(__ENV.KB_IDS, '');

const failRate = new Rate('agent_question_fail');
const bizLatency = new Trend('agent_question_biz_latency', true);

export const options = {
  scenarios: {
    agent_question: {
      executor: __ENV.VUS ? 'constant-vus' : 'ramping-vus',
      ...(__ENV.VUS
        ? { vus: parseInt(__ENV.VUS, 10), duration: __ENV.DURATION || '1m' }
        : {
            startVUs: 1,
            stages: [
              { duration: '30s', target: 2 },
              { duration: '1m', target: 5 },
              { duration: '30s', target: 0 },
            ],
          }),
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<25000', 'p(99)<40000'],
    agent_question_fail: ['rate<0.2'],
  },
};

export function setup() {
  return { token: resolveToken() };
}

export default function (data) {
  const payload = {
    questionCount: QUESTION_COUNT,
    forceCreate: true,
    skillId: SKILL_ID,
    difficulty: 'mid',
  };
  if (KB_IDS.length > 0) {
    payload.knowledgeBaseIds = KB_IDS;
  }

  const res = http.post(`${BASE_URL}/api/v1/interviews/sessions`, JSON.stringify(payload),
    { headers: authHeaders(data.token) });
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
