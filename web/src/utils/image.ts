/**
 * 上传前在浏览器里压缩照片（RFC 001 §10）：长边压到 1600px、JPEG 质量 0.8。
 * 手机照片一张 3-8MB，压完三百 KB 左右，传得快，服务器硬盘和备份也省。
 * 浏览器把图画到 canvas 上时已经按照片里的方向信息摆正了，压出来的图不会是歪的
 */
const MAX_EDGE = 1600
const QUALITY = 0.8
/** 本来就小于 300KB 的不压：省不了多少，还要多损失一次画质 */
const SKIP_BELOW = 300 * 1024
/** 只压位图。GIF 压了会丢动画，SVG 是矢量图，都原样传 */
const COMPRESSIBLE = /^image\/(jpeg|png|webp|bmp|heic|heif|avif)$/i

/** 返回压好的 JPEG；不用压或压不了的（浏览器解不开的格式，如 Chrome 里的 HEIC）返回原文件 */
export async function compressImage(file: File): Promise<File> {
  if (!COMPRESSIBLE.test(file.type) || file.size < SKIP_BELOW) return file
  const url = URL.createObjectURL(file)
  try {
    const image = new Image()
    image.src = url
    await image.decode()
    const scale = Math.min(1, MAX_EDGE / Math.max(image.naturalWidth, image.naturalHeight))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(image.naturalWidth * scale)
    canvas.height = Math.round(image.naturalHeight * scale)
    const context = canvas.getContext('2d')
    if (!context) return file
    // JPEG 没有透明：抠过图的 PNG，透明的地方会变成黑的，所以先铺一层白底。
    // 这里写死白色、不用 tokens.css 的变量：这层底色会永远留在上传的图片文件里，是图片的数据，不是界面样式。
    // 用了变量，以后界面底色改成坯布色，新传的商品图就都带上那层色了
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY))
    // 原图已经压得很狠时，再压一遍反而更大，就传原图
    if (!blob || blob.size >= file.size) return file
    return new File([blob], `${file.name.replace(/\.[^.]*$/, '')}.jpg`, { type: 'image/jpeg', lastModified: file.lastModified })
  } catch {
    return file
  } finally {
    URL.revokeObjectURL(url)
  }
}
