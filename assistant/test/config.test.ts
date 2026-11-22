import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadConfig } from '../src/config.ts'

const DB = { ASSISTANT_DATABASE_URL: 'postgres://scm_assistant@db/scm' }

test('模型没配：llm 是 null，服务照样能起；默认限额', () => {
  const c = loadConfig({ ...DB, ASSISTANT_LLM_BASE_URL: 'https://x/v1' })
  assert.equal(c.llm, null, '只有地址没有模型名也算没配')
  assert.equal(c.port, 8091)
  assert.equal(c.appBaseUrl, 'http://app:8080')
  assert.equal(c.limits.userDailyRequests, 200)
})

test('配了模型：地址去掉末尾的 /，默认外部模型、要用量', () => {
  const c = loadConfig({ ...DB, ASSISTANT_LLM_BASE_URL: 'https://llm.example/v1/', ASSISTANT_LLM_MODEL: 'qwen', ASSISTANT_LLM_API_KEY: 'k' })
  assert.deepEqual(c.llm, { baseUrl: 'https://llm.example/v1', apiKey: 'k', model: 'qwen', mode: 'external', streamUsage: true, timeoutMs: 90_000 })
})

test('填错的直接起不来，说清楚哪一项', () => {
  assert.throws(() => loadConfig({}), /ASSISTANT_DATABASE_URL/)
  assert.throws(() => loadConfig({ ...DB, ASSISTANT_LLM_BASE_URL: 'ftp://x', ASSISTANT_LLM_MODEL: 'm' }), /http/)
  assert.throws(() => loadConfig({ ...DB, ASSISTANT_USER_DAILY_REQUESTS: '0' }), /ASSISTANT_USER_DAILY_REQUESTS/)
  assert.throws(() => loadConfig({ ...DB, ASSISTANT_MAX_STEPS: 'abc' }), /ASSISTANT_MAX_STEPS/)
})
