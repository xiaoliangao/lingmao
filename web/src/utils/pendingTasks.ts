/** 同一编辑会话的异步附件任务；丢弃会话后晚到结果不能写回新表单。 */
export function createPendingTasks(changed: (pending: number, generation: number) => void = () => {}) {
  let generation = 0
  const tasks = new Map<symbol, { promise: Promise<boolean>; resolve: (ok: boolean) => void }>()
  const notify = () => changed(tasks.size, generation)
  function begin() {
    const id = Symbol('upload')
    const started = generation
    let resolve: (ok: boolean) => void = () => {}
    const promise = new Promise<boolean>((done) => { resolve = done })
    tasks.set(id, { promise, resolve })
    notify()
    return {
      current: () => started === generation && tasks.has(id),
      finish(ok: boolean) { tasks.get(id)?.resolve(ok); tasks.delete(id); notify() },
    }
  }
  function discard() {
    generation++
    for (const task of tasks.values()) task.resolve(false)
    tasks.clear()
    notify()
  }
  async function wait(): Promise<boolean> {
    while (tasks.size) {
      if ((await Promise.all([...tasks.values()].map((task) => task.promise))).some((ok) => !ok)) return false
    }
    return true
  }
  return { begin, discard, wait }
}
