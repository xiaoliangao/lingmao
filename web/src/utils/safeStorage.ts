/** Storage is an optional convenience. Quota errors and privacy mode cannot break a page. */
export function readStorage(key: string, session = false): string | null {
  try { return (session ? sessionStorage : localStorage).getItem(key) } catch { return null }
}
export function writeStorage(key: string, value: string | null, session = false): boolean {
  try {
    const storage = session ? sessionStorage : localStorage
    if (value === null) storage.removeItem(key)
    else storage.setItem(key, value)
    return true
  } catch { return false }
}
