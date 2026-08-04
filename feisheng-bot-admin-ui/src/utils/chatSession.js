export function createChatSessionId(scope = 'playground') {
  const randomPart = globalThis.crypto?.randomUUID?.()
    || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${scope}-${randomPart}`.slice(0, 100)
}
