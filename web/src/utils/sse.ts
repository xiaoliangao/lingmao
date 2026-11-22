/**
 * 读 SSE（text/event-stream，智能助手的问话用，docs/api/08-智能助手.md §2.3）。浏览器自带的 EventSource 只能 GET、不能带令牌，所以自己读。
 * 照规范：一行一个字段，空行结束一个事件；event: 是事件名（没有是 message），data: 可以有多行（用 \n 连起来）；
 * : 开头的是注释，id、retry 不管；流结束时还没遇到空行的那个事件丢掉。换行认 \n、\r\n、\r，块和块之间切开的字、切开的 \r\n 都接得上。
 * onEvent 抛错时停下来、把错抛给调用方（流由调用方取消）
 */
export async function readSse(stream: ReadableStream<Uint8Array>, onEvent: (event: string, data: string) => void): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let event = ''
  let data: string[] = []

  const line = (raw: string) => {
    if (raw === '') {
      if (data.length) onEvent(event || 'message', data.join('\n'))
      event = ''
      data = []
      return
    }
    if (raw.startsWith(':')) return
    const colon = raw.indexOf(':')
    const field = colon < 0 ? raw : raw.slice(0, colon)
    let value = colon < 0 ? '' : raw.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }

  /** 把 buffer 里完整的行交出去；end：流结束了，末尾单独的 \r 也算换行 */
  const drain = (end: boolean) => {
    for (;;) {
      const at = buffer.search(/[\r\n]/)
      if (at < 0) return
      if (buffer[at] === '\r' && at === buffer.length - 1 && !end) return // 可能是切开的 \r\n，等下一块
      const width = buffer[at] === '\r' && buffer[at + 1] === '\n' ? 2 : 1
      const raw = buffer.slice(0, at)
      buffer = buffer.slice(at + width)
      line(raw)
    }
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      drain(false)
    }
    buffer += decoder.decode()
    drain(true)
  } finally {
    reader.releaseLock()
  }
}
