/** keyCode 229 covers older IME implementations that do not populate isComposing. */
export function isComposing(event: Pick<KeyboardEvent, 'isComposing' | 'keyCode'>): boolean {
  return event.isComposing || event.keyCode === 229
}
/** Keep the IME's keyboard default intact; stop business handlers and composing form submits separately. */
export function installImeGuard(target: Document): () => void {
  let composing = false
  let committingEnter = false
  let suppressEnterUp = false
  let commitTimer: ReturnType<typeof setTimeout> | undefined
  const reset = () => { composing = false; committingEnter = false; suppressEnterUp = false; clearTimeout(commitTimer) }
  const start = () => { reset(); composing = true }
  const end = () => {
    composing = false
    clearTimeout(commitTimer)
    commitTimer = setTimeout(() => { committingEnter = false }, 0)
  }
  const guard = (event: KeyboardEvent) => {
    const enter = event.key === 'Enter' || event.code === 'Enter' || event.code === 'NumpadEnter'
    if (event.type === 'keydown' && !isComposing(event) && !composing) suppressEnterUp = false
    if ((enter || event.keyCode === 229) && (isComposing(event) || composing || (event.type === 'keyup' && suppressEnterUp))) {
      if (event.type === 'keydown' && enter) { committingEnter = true; suppressEnterUp = true }
      event.stopImmediatePropagation()
      // Do not preventDefault: the IME still needs to commit the selected word.
    }
    if (event.type === 'keyup' && enter) { committingEnter = false; suppressEnterUp = false }
  }
  const submit = (event: Event) => {
    if (!composing && !committingEnter) return
    event.preventDefault()
    event.stopImmediatePropagation()
  }
  target.addEventListener('compositionstart', start, true)
  target.addEventListener('compositionend', end, true)
  target.addEventListener('submit', submit, true)
  target.addEventListener('blur', reset, true)
  target.addEventListener('keydown', guard, true)
  target.addEventListener('keyup', guard, true)
  return () => {
    target.removeEventListener('keydown', guard, true)
    target.removeEventListener('keyup', guard, true)
    target.removeEventListener('compositionstart', start, true)
    target.removeEventListener('compositionend', end, true)
    target.removeEventListener('submit', submit, true)
    target.removeEventListener('blur', reset, true)
    reset()
  }
}
