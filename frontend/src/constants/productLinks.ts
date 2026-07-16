export interface ExternalResource {
  id: string;
  name: string;
  url: string;
  description: string;
  badge: string;
}

export const RECRUITMENT_SOURCES: readonly ExternalResource[] = [
  {
    id: 'offer-coming',
    name: 'OfferComing',
    url: 'https://offercoming.cn/',
    description: '汇总校招与实习岗位，适合快速查看公司、岗位和投递时间。',
    badge: '岗位聚合',
  },
  {
    id: 'kama-delivery',
    name: '卡码投递表',
    url: 'https://toudi.kamacoder.com/',
    description: '按公司、城市和批次筛选校招信息，查看当前开放岗位。',
    badge: '投递信息',
  },
  {
    id: 'tencent-sheet',
    name: '秋招信息腾讯文档',
    url: 'https://docs.qq.com/smartsheet/DTkRMUVhoUWJXZEhJ?tab=tvVDZj&viewId=vmLdET',
    description: '人工整理的秋招信息表，作为企业官网之外的补充信息源。',
    badge: '人工整理',
  },
] as const;

export const LEARNING_RESOURCES: readonly ExternalResource[] = [
  {
    id: 'leetcode-cn',
    name: '力扣中国',
    url: 'https://leetcode.cn/',
    description: '练习算法题和参加周赛，补充平台内 Hot 100 之外的题目。',
    badge: '算法平台',
  },
  {
    id: 'programmercarl',
    name: '代码随想录',
    url: 'https://programmercarl.com/',
    description: '按数据结构与算法专题刷题，适合制定系统复习路线。',
    badge: '算法路线',
  },
  {
    id: 'java-guide',
    name: 'JavaGuide',
    url: 'https://javaguide.cn/',
    description: '整理 Java 后端基础与高频面试知识，便于按主题查漏补缺。',
    badge: 'Java 后端',
  },
  {
    id: 'mianzha-nixi',
    name: '面渣逆袭',
    url: 'https://javabetter.cn/sidebar/sanfene/nixi.html',
    description: '按专题梳理后端常见面试题，适合面试前集中复习。',
    badge: '面试复习',
  },
] as const;
