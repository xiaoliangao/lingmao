import assert from 'node:assert/strict'
import test from 'node:test'
import { createRequire } from 'node:module'
import { isDismissed, isFormValidationError } from '../src/utils/expectedError.ts'

// el-form 的 validate() 不传回调时 reject 的就是 async-validator 的 fields；用真库生成，不手写形状。
// 它是 element-plus 的依赖（pnpm 不提到顶层），从 element-plus 那里找，校验规则和页面上用的同一份
const fromElementPlus = createRequire(createRequire(import.meta.url).resolve('element-plus/package.json'))
const validator = fromElementPlus('async-validator')
const Schema = validator.default ?? validator
async function rejectedFields(descriptor, value) {
  try {
    await new Schema(descriptor).validate(value)
  } catch (e) {
    return e.fields
  }
  throw new Error('should have failed')
}

test('form validation rejections are expected', async () => {
  const fields = await rejectedFields(
    {
      name: { required: true, message: '请输入名称' },
      lines: { type: 'array', required: true, min: 1, message: '至少选一条品类线' },
      confirm: { validator: (_r, _v, cb) => cb(new Error('两次输入的新密码不一样')) },
    },
    { name: '', lines: [], confirm: 'x' },
  )
  assert.deepEqual(Object.keys(fields).sort(), ['confirm', 'lines', 'name'])
  assert.equal(isFormValidationError(fields), true)
})

test('real errors and lookalikes are not swallowed', () => {
  assert.equal(isFormValidationError(new TypeError('x is undefined')), false)
  assert.equal(isFormValidationError({}), false)
  assert.equal(isFormValidationError({ list: [] }), false)
  assert.equal(isFormValidationError({ list: [{ id: 1 }] }), false)
  assert.equal(isFormValidationError({ code: 500, message: 'boom' }), false)
  assert.equal(isFormValidationError(null), false)
  assert.equal(isFormValidationError('cancel'), false)
  assert.equal(isFormValidationError(Object.assign(Object.create(null), { a: [{ message: 'm' }] })), false)
})

test('message box cancel / close are dismissals', () => {
  assert.equal(isDismissed('cancel'), true)
  assert.equal(isDismissed('close'), true)
  assert.equal(isDismissed('confirm'), false)
  assert.equal(isDismissed(new Error('cancel')), false)
})
