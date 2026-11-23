import type { ObjectTagSnapshot, PublicTag, TagReplace } from '@/types/tag'
import { TAG_LIMIT } from '@/types/tag'
import { ApiError } from './request'
import { ErrorCode } from './errorCodes'

export type TagChoice =
  | { key: string; kind: 'saved'; tag: PublicTag }
  | { key: string; kind: 'input'; raw: string; name: string }

/** 预览只用于输入提示；新增文本仍原样提交，由后端执行唯一一次规范化。 */
export function previewTagName(raw: string): string {
  if (raw.length > 1024) throw new Error('输入太长，最多 1024 个字符单元')
  const normalized = raw.normalize('NFKC')
  if (/[\p{Cc}\p{Cs},\u202a-\u202e\u2066-\u2069]/u.test(normalized)) throw new Error('标签不能含逗号或控制字符')
  const strip = (value: string) => value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
  let name = strip(normalized)
  if (name.startsWith('#')) name = strip(name.slice(1))
  if (!name || [...name].length > 64) throw new Error('标签名称需要 1 至 64 个字或符号')
  return name
}

/** 显示名可能本来就以 # 开头，回填时只补传输标记，不再次剥离。 */
export function savedTagInput(name: string): string { return '#' + name }
export function tagChoiceName(choice: TagChoice): string { return choice.kind === 'saved' ? choice.tag.name : choice.name }
export function choiceOf(tag: PublicTag): TagChoice { return { key: 'tag:' + tag.id, kind: 'saved', tag: { ...tag } } }

export function tagReplace(snapshot: ObjectTagSnapshot, choices: readonly TagChoice[]): TagReplace {
  if (choices.length > TAG_LIMIT) throw new Error('每个对象最多 30 个标签')
  const current = new Set(snapshot.tags.map((tag) => tag.id))
  return {
    version: snapshot.version,
    names: choices.map((choice) => choice.kind === 'saved' ? savedTagInput(choice.tag.name) : choice.raw),
    retainIds: choices.flatMap((choice) => choice.kind === 'saved' && current.has(choice.tag.id) ? [choice.tag.id] : []),
    // 被移除项也必须携带原版本，防止全局改名/归档与局部替换发生静默覆盖。
    tagVersions: Object.fromEntries(snapshot.tags.map((tag) => [tag.id, tag.version])),
  }
}

export function tagError(cause: unknown): string {
  if (cause instanceof ApiError) {
    const message = cause.code === ErrorCode.STATE_CHANGED ? '标签已被其他人修改，请重新读取并核对当前选择'
      : cause.code === 403 ? '当前没有这项标签操作权限'
      : cause.code === 404 ? '对象或标签已不可见，请重新读取'
      : cause.message
    return message + (cause.traceId ? '（编号 ' + cause.traceId + '）' : '')
  }
  return '暂时无法读取或保存标签，请重试'
}
