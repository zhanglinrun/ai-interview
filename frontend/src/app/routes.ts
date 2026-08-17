/**
 * 应用级路由常量。页面只依赖这里的产品入口，不直接散落字符串。
 */
export const ROUTES = {
  dashboard: '/dashboard',
  interview: '/interview',
  recruitment: '/recruitment',
  resources: '/resources',
  profile: '/profile',
  resumeUpload: '/upload',
  knowledgebaseUpload: '/knowledgebase/upload',
} as const;
