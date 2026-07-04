// 后端吞吐压测（k6）——非 LLM 接口，用来拿真实 P95/P99/QPS
//
// 为什么单独一个脚本：rag-query / interview-create / agent-ab 都打 LLM 链路，单请求就要
// 数秒，且受 API 成本和 @RateLimit 约束，只能做「单请求延迟 smoke」，QPS 没有吞吐意义。
// 要证明「后端 Web 层在高并发下的吞吐与延迟」，必须压一个不打 LLM、不打外部 embedding 的
// 纯 DB/Redis 接口。这里选 GET /api/knowledgebase/list（列表查询，走 MySQL + 可能的 Redis）。
//
// 运行：
//   k6 run -e BASE_URL=http://localhost:8082 -e TOKEN=xxx eval/loadtest/kb-list-throughput.js
//   k6 run -e BASE_URL=http://localhost:8082 -e TOKEN=xxx -e VUS=50 -e DURATION=1m eval/loadtest/kb-list-throughput.js
//
// 关注指标：
//   - http_req_duration  P95 / P99（纯后端延迟，应为毫秒级）
//   - http_reqs          吞吐（真·QPS，非 1/延迟）
//   - kb_list_fail       业务失败率（HTTP 200 但 Result.code 非 0 也算失败）
//
// 提示：GET 列表接口通常无强限流；若返回 8001，说明命中 @RateLimit，压系统极限时临时调高阈值。
//
// 附带用法：把 MODE=missing 传进来会改压 GET /api/knowledgebase/{randomBigId}，
// 全部命中「不存在 ID」，用于验证短 TTL 空值缓存对缓存穿透的防护效果
// （对照：MODE=missing 且后端关闭空值缓存时，DB 查询次数应显著升高）。

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082';
const TOKEN = __ENV.TOKEN || '';
const MODE = __ENV.MODE || 'list'; // list | missing

const failRate = new Rate('kb_list_fail');
const bizLatency = new Trend('kb_list_biz_latency', true);

export const options = {
  scenarios: {
    kb_throughput: {
      executor: __ENV.VUS ? 'constant-vus' : 'ramping-vus',
      ...(__ENV.VUS
        ? { vus: parseInt(__ENV.VUS, 10), duration: __ENV.DURATION || '1m' }
        : {
            startVUs: 5,
            stages: [
              { duration: '20s', target: 20 },
              { duration: '40s', target: 50 },
              { duration: '40s', target: 100 },
              { duration: '20s', target: 0 },
            ],
          }),
    },
  },
  thresholds: {
    // 纯后端接口的期望线：毫秒级延迟、极低失败率。超过会被 k6 标红，方便发现退化。
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    kb_list_fail: ['rate<0.05'],
  },
};

function targetUrl() {
  if (MODE === 'missing') {
    // 稳定命中「不存在的知识库 ID」，走空值缓存防穿透路径
    const bigId = 900000000 + Math.floor(Math.random() * 1000);
    return `${BASE_URL}/api/knowledgebase/${bigId}`;
  }
  return `${BASE_URL}/api/knowledgebase/list`;
}

export default function () {
  const headers = {};
  if (TOKEN) {
    headers.Authorization = `Bearer ${TOKEN}`;
  }
  const res = http.get(targetUrl(), { headers });
  bizLatency.add(res.timings.duration);

  // 统一响应 Result<T>：HTTP 200 且 code==0/200 才算成功；missing 模式下「查不到」也是正常返回。
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
