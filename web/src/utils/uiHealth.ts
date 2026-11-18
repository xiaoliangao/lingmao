import { ref } from 'vue'
const unsavedChecks = new Set<() => boolean>()
export function trackUnsavedForm(check: () => boolean): () => void { unsavedChecks.add(check); return () => { unsavedChecks.delete(check) } }
export function hasUnsavedForms(): boolean { return [...unsavedChecks].some((check) => check()) }
export const updateAvailable = ref(false)
export const accountChanged = ref(false)
