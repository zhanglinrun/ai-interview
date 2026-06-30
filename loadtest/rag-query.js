// RAG 问答接口压测（k6）
//
// 压测目标：POST /api/knowledgebase/query（非流式），这是 RAG 全链路最重的同步接口，
// 覆盖查询改写 + ES KNN/全文检索 + rerank + LLM 生成。
//
// 运行：
//   k6 run -e BASE_URL=http://localhost:8082 -e TOKEN=xxx -e KB_IDS=1 loadtest/rag-query.js
// 自定义负载档位（默认走下方 stages）：
//   k6 run -e VUS=20 -e DURATION=2m loadtest/rag-query.js
//
// 关注指标：
//   - http_req_duration  P95 / P99（端到端延迟）
//   - http_reqs          吞吐（QPS）
//   - rag_query_fail     业务失败率（HTTP 200 但 Result.code 非 0 也算失败）
//
// 注意：接口侧有 @RateLimit（GLOBAL=10 / IP=10，单位时间窗口），高并发下会出现 8001 限流。
// 压"系统极限"时建议临时调高或关闭限流；压"真实带限流表现"时保留，把 429/限流计入失败率观察。

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082';
const TOKEN = __ENV.TOKEN || '';
const KB_IDS = (__ENV.KB_IDS || '1')
  .split(',')
  .map((s) => parseInt(s.trim(), 10))
  .filter((n) => !Number.isNaN(n));

// 一组覆盖长短查询的问题：短查询走更大的 topK 召回，长查询走精排，贴近真实分布。
const QUESTIONS = [
  'Redis',
  'MySQL 索引',
  '什么是 Spring 的 IOC 和 AOP',
  'HTTP 和 HTTPS 的区别是什么',
  '介绍一下 JVM 的垃圾回收机制以及常见的 GC 算法',
  'TCP 三次握手和四次挥手的过程，以及为什么需要三次',
  '分布式系统里如何保证缓存和数据库的一致性',
  '线程池的核心参数有哪些，拒绝策略怎么选',
];

const failRate = new Rate('rag_query_fail');
const bizLatency = new Trend('rag_query_biz_latency', true);

export const options = {
  scenarios: {
    rag_query: {
      executor: __ENV.VUS ? 'constant-vus' : 'ramping-vus',
      ...(__ENV.VUS
        ? { vus: parseInt(__ENV.VUS, 10), duration: __ENV.DURATION || '1m' }
        : {
            startVUs: 1,
            stages: [
              { duration: '30s', target: 5 },
              { duration: '1m', target: 10 },
              { duration: '1m', target: 20 },
              { duration: '30s', target: 0 },
            ],
          }),
    },
  },
  thresholds: {
    // 这些阈值是“期望线”，压测结论以实际 summary 为准；超过会被 k6 标红，方便快速发现退化。
    http_req_duration: ['p(95)<8000', 'p(99)<15000'],
    rag_query_fail: ['rate<0.2'],
  },
};

export default function () {
  const question = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  const payload = JSON.stringify({ knowledgeBaseIds: KB_IDS, question });
  const headers = { 'Content-Type': 'application/json' };
  if (TOKEN) {
    headers.Authorization = `Bearer ${TOKEN}`;
  }
  const params = { headers };

  const res = http.post(`${BASE_URL}/api/knowledgebase/query`, payload, params);
  bizLatency.add(res.timings.duration);

  // 统一响应 Result<T>：HTTP 200 且 code==0 才算成功。限流(8001)、业务异常都计入失败。
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
