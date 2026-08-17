import { channelTypeText } from './displayText.js'

const BUILT_IN_CHANNELS = ['web', 'wechat', 'dingtalk', 'playground', 'other']

export async function loadChannelOptions(request) {
  const response = await request.get('/admin/channel/config/list', {
    params: { page: 1, size: 100 }
  })
  const records = response.data?.records || []
  const labels = new Map()

  records.forEach(channel => {
    const type = String(channel?.channelType || '').trim()
    if (!type || labels.has(type)) return
    labels.set(type, String(channel.channelName || '').trim() || channelTypeText(type))
  })
  BUILT_IN_CHANNELS.forEach(type => {
    if (!labels.has(type)) labels.set(type, channelTypeText(type))
  })

  return [...labels.entries()].map(([value, name]) => ({
    value,
    label: name === channelTypeText(value)
      ? name
      : `${name}（${channelTypeText(value)}）`
  }))
}
