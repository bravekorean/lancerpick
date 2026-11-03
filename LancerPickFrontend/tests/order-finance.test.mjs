import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { getOrder, payOrder, cancelOrder, confirmOrder } from '../src/features/orders/api.ts'
import { clearCsrfToken } from '../src/shared/api/client.ts'
const original = globalThis.fetch
afterEach(() => { globalThis.fetch = original; clearCsrfToken() })
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
test('order financial commands use CSRF and send no client amount or member id', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf') return json({ token: 'test', headerName: 'X-CSRF-TOKEN' })
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.headers.get('X-CSRF-TOKEN'), 'test')
    calls.push([url, options.body])
    return json({ id: '9007199254740993', status: 'COMPLETED' })
  }
  await payOrder('9007199254740993'); await cancelOrder('9007199254740993', 'reason'); await confirmOrder('9007199254740993')
  assert.deepEqual(calls, [['/api/orders/9007199254740993/payment', '{"agreedToTerms":true}'], ['/api/orders/9007199254740993/cancel', '{"reason":"reason"}'], ['/api/orders/9007199254740993/confirm', undefined]])
})
test('invalid identifiers never reach network and failed payment is not replayed', async () => {
  let writes = 0
  globalThis.fetch = async (url) => {
    if (url === '/api/auth/csrf') return json({ token: 'test', headerName: 'X-CSRF-TOKEN' })
    writes++; return json({ code: 'INSUFFICIENT_BALANCE', message: 'insufficient', fieldErrors: [], traceId: 'test' }, 409)
  }
  for (const id of ['0', '-1', '../me', '9223372036854775808']) assert.throws(() => getOrder(id))
  assert.equal(writes, 0)
  await assert.rejects(payOrder('1'), error => error.code === 'INSUFFICIENT_BALANCE')
  assert.equal(writes, 1)
})
