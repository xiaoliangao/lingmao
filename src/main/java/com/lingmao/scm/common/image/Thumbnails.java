package com.lingmao.scm.common.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 图片缩略图（RFC 001 §10）：长边 320px，列表、卡片上显示它，点开看原图。只用 JDK 自带的 ImageIO，不加依赖。
 * 只做 jpg、png：前端上传前已经压成 jpg（透明 PNG 铺白底），png 是直接拖进来的截图之类；gif、webp ImageIO 读不全，不做。
 * <p>
 * 大照片不整张解码：按整数倍隔行隔列地读（ImageReadParam 的 subsampling），只解出两倍目标大小左右，
 * 一张 4000×3000 的照片解出来是 666×500，几百 KB 内存；再对半缩几次、最后一步缩到 320，比一步缩下去清楚。
 * 读不出来（坏图、无法解码的 jpg、只有扩展名是 jpg）抛 IOException，由上传服务拒绝并清理文件。
 */
public final class Thumbnails {

    /** 缩略图的长边，像素 */
    public static final int LONG_SIDE = 320;

    /** 超过一亿像素的图不做：正常照片几千万像素，再大多半是故意的，解码太费时间 */
    private static final long MAX_PIXELS = 100_000_000L;

    private static final Set<String> SUPPORTED = Set.of("jpg", "jpeg", "png");

    private Thumbnails() {
    }

    /** 这种扩展名的文件做不做缩略图 */
    public static boolean supports(String ext) {
        return ext != null && SUPPORTED.contains(ext.toLowerCase(Locale.ROOT));
    }

    /**
     * 读 source，把长边 320 的缩略图写到 target。ext 是原图的扩展名，缩略图用同样的格式：
     * jpg 还是 jpg；png 还是 png，透明的地方保持透明。原图长边本来就不到 320 的，按原尺寸存一份，不放大
     */
    public static void create(Path source, Path target, String ext) throws IOException {
        boolean png = "png".equalsIgnoreCase(ext);
        BufferedImage image = readSubsampled(source);
        BufferedImage thumb = shrink(image, png);
        if (!ImageIO.write(thumb, png ? "png" : "jpg", target.toFile())) {
            throw new IOException("没有能写 " + ext + " 的 ImageIO 写入器");
        }
    }

    /** 读第一帧，大图隔行隔列地读：只解出长边 640~1280 左右的一张 */
    private static BufferedImage readSubsampled(Path source) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(source.toFile())) {
            if (in == null) {
                throw new IOException("打不开文件");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("不是认得的图片格式");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new IOException("图片太大，不做缩略图: " + width + "x" + height);
                }
                int step = Math.max(1, Math.max(width, height) / (LONG_SIDE * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 先对半缩到不足两倍目标大小，最后一步缩到长边 320；本来就小的只换一下像素格式 */
    private static BufferedImage shrink(BufferedImage image, boolean keepAlpha) {
        BufferedImage current = image;
        int longSide = Math.max(current.getWidth(), current.getHeight());
        while (longSide / 2 >= LONG_SIDE) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2, keepAlpha);
            longSide = Math.max(current.getWidth(), current.getHeight());
        }
        double scale = Math.min(1.0, (double) LONG_SIDE / longSide);
        int width = Math.max(1, (int) Math.round(current.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(current.getHeight() * scale));
        return draw(current, width, height, keepAlpha);
    }

    /**
     * 画到一张新图上。jpg 没有透明通道：先铺白底再画，透明的地方是白的（和前端压缩时一样），不铺的话是黑的；
     * 也顺便把灰度图、调色板图这些各式各样的像素格式统一成 RGB / ARGB，ImageIO 写 jpg 时才不报错
     */
    private static BufferedImage draw(BufferedImage source, int width, int height, boolean keepAlpha) {
        BufferedImage target = new BufferedImage(width, height,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            if (!keepAlpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
