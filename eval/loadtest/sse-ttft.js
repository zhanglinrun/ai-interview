// SSE 首字延迟（TTFT / TTFB）量化（k6）
//
// 目标：POST /api/v1/knowledge-bases/query/stream —— 流式 RAG 问答，SSE 推送 progress/reference/token。
// 大厂关注「流式首字延迟」：用户多久能看到第一个字。这里量化两个指标：
//   - ttft_proxy_ms = http_req_waiting（首字节/首个 SSE 事件到达延迟）。
//     注意：本接口会先推 progress（理解/检索/排序）事件再推 LLM token，
//     所以 TTFB 是「首个 SSE 事件延迟」，是真 TTFT（首 token）的**下界代理**。
//   - stream_total_ms = http_req_duration（整段流式结束的端到端延迟）。
// 要拿「首个 LLM token」的精确 TTFT，用文末 curl -N 方式抓 event:token 的时间戳。
//
// 前置：test 用户名下要有一个已 VECTOR_STORED 的知识库，把它的 id 传给 KB_IDS。
//
// 运行（流式接口有 @RateLimit GLOBAL=5/IP=5，用低并发顺序采样）：
//   k6 run -e BASE_URL=http://localhost:8082 -e TOKEN=xxx -e KB_IDS=1 eval/loadtest/sse-ttft.js
//
// 精确首 token（PowerShell + curl，记录第一条 token 事件相对起始的毫秒）：
//   $t0=Get-Date; curl.exe -N -s -H "satoken: $TOKEN" -H "Content-Type: application/json" `
//     -d '{"knowledgeBaseIds":[1],"question":"什么是缓存穿透"}' `
//     http://localhost:8082/api/v1/knowledge-bases/query/stream | ForEach-Object {
//       if ($_ -match 'token') { Write-Output ("TTFT_ms=" + ((Get-Date)-$t0).TotalMilliseconds); break } }

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL, authHeaders, parseIds, resolveToken } from './helpers.js';

const KB_IDS = parseIds(__ENV.KB_IDS, '1');

const QUESTIONS = [
  '什么是缓存穿透，如何防止',
  'Redis 为什么快',
  'MySQL 的 MVCC 是怎么实现的',
  'Spring 是怎么解决循环依赖的',
];

const ttftProxy = new Trend('ttft_proxy_ms', true); // http_req_waiting ≈ 首事件延迟（TTFT 下界）
const streamTotal = new Trend('stream_total_ms', true);
const failRate = new Rate('ttft_fail');

export const options = {
  scenarios: {
    ttft: {
      executor: 'per-vu-iterations',
      vus: parseInt(__ENV.VUS || '1', 10),
      iterations: parseInt(__ENV.ITER || '5', 10),
      maxDuration: '3m',
    },
  },
  thresholds: {
    // 期望线：首事件（TTFB）应远小于整段生成时长。
    ttft_proxy_ms: ['p(95)<4000'],
  },
};

export function setup() {
  return { token: resolveToken() };
}

export default function (data) {
  const question = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  const payload = JSON.stringify({ knowledgeBaseIds: KB_IDS, question });
  const headers = authHeaders(data.token, { Accept: 'text/event-stream' });

  const res = http.post(`${BASE_URL}/api/v1/knowledge-bases/query/stream`, payload, { headers });
  ttftProxy.add(res.timings.waiting);
  streamTotal.add(res.timings.duration);

  const ok = res.status === 200;
  failRate.add(!ok);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'got stream body': (r) => r.body && r.body.length > 0,
  });

  // 流式接口限流 5/min，顺序采样时留间隔避免 8001。
  sleep(parseInt(__ENV.SLEEP || '13', 10));
}
