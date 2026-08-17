const CHANNEL_TYPE_TEXT = {
  web: '网页',
  wechat: '企业微信',
  dingtalk: '钉钉',
  playground: '试聊台',
  other: '其他'
}

const PRIORITY_TEXT = {
  P0: '紧急',
  P1: '高',
  P2: '中',
  P3: '低'
}

const PROVIDER_TEXT = {
  openai: '开放式智能',
  deepseek: '深度求索',
  qwen: '通义千问',
  zhipu: '智谱',
  azure: '微软云',
  other: '其他'
}

const MODEL_TYPE_TEXT = {
  LLM: '大语言模型',
  Extraction: '知识抽取模型',
  Embedding: '向量嵌入',
  Rerank: '检索重排',
  Speech: '语音识别',
  TTS: '语音合成'
}

export function channelTypeText(value) {
  return CHANNEL_TYPE_TEXT[value] || '未知渠道'
}

export function channelNameText(row) {
  const name = String(row?.channelName || '').trim()
  if (name && name !== row?.channelType) return name
  return channelTypeText(row?.channelType)
}

export function priorityText(value) {
  return PRIORITY_TEXT[value] || '中'
}

export function providerText(value) {
  return PROVIDER_TEXT[String(value || '').toLowerCase()] || '其他供应商'
}

export function modelTypeText(value) {
  return MODEL_TYPE_TEXT[value] || '大语言模型'
}

export function confidenceText(value) {
  return { high: '高', medium: '中', low: '低' }[value] || '未知'
}

export function safetyActionText(value) {
  return {
    BLOCK: '拦截',
    REPLY_FIXED: '固定回复',
    HANDOFF: '转人工',
    LOG_ONLY: '仅记录',
    ALLOW: '通过',
    PASS: '通过'
  }[value] || '未指定'
}

export function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(date)
}

export function operatorText(user) {
  const realName = user?.realName?.trim()
  if (realName && !/[A-Za-z]/.test(realName)) return realName
  return '系统管理员'
}

export function contentText(value, fallback = '') {
  if (value == null || value === '') return fallback
  return String(value)
}

export function localizedSystemText(value, fallback = '系统信息') {
  if (value == null || value === '') return fallback
  const translated = String(value)
    .replace(/\bOpenAI\b/gi, '开放式智能')
    .replace(/\bDeepSeek\b/gi, '深度求索')
    .replace(/\bLLM\b/gi, '大语言模型')
    .replace(/\bExtraction\b/gi, '知识抽取模型')
    .replace(/\bEmbedding\b/gi, '向量嵌入')
    .replace(/\bRerank\b/gi, '检索重排')
    .replace(/\bTTS\b/gi, '语音合成')
    .replace(/\bAI\b/gi, '智能助手')
    .replace(/\bFAQ\b/gi, '常见问题')
    .replace(/\bSLA\b/gi, '响应时限')
    .replace(/\bOCR\b/gi, '文字识别')
    .replace(/\bRAG\b/gi, '检索增强')
    .replace(/\bAPI\b/gi, '接口')
    .replace(/\bID\b/gi, '标识')
    .replace(/\s{2,}/g, ' ')
    .trim()
  return translated || fallback
}

export function localizedErrorText(value, fallback = '请求失败，请稍后重试') {
  if (!value) return fallback
  const translated = localizedSystemText(value, '')
    .replace(/^[\s:：,，、;；-]+|[\s:：,，、;；-]+$/g, '')
  return /[\u3400-\u9fff]/.test(translated) ? translated : fallback
}
