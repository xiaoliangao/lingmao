package com.lingmao.scm.common.file;

import com.lingmao.scm.common.exception.BizException;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 按文件内容的特征和有上限的 Office ZIP 检查判断类型；浏览器给的 MIME 类型不作数。 */
public final class UploadContent {
    private UploadContent() {}
    private static final long MAX_EXPANDED = 64L * 1024 * 1024;
    private static final long MAX_ENTRY = 16L * 1024 * 1024;

    public static void verify(Path file, String extension) throws IOException {
        byte[] head;
        try (var input = Files.newInputStream(file)) { head = input.readNBytes(32); }
        boolean valid = switch (extension) {
            case "jpg", "jpeg" -> starts(head, 0xff, 0xd8, 0xff);
            case "png" -> starts(head, 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10);
            case "gif" -> ascii(head, "GIF87a") || ascii(head, "GIF89a");
            case "webp" -> ascii(head, "RIFF") && head.length >= 16
                    && new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            case "pdf" -> ascii(head, "%PDF-");
            case "ai" -> ascii(head, "%PDF-") || ascii(head, "%!PS-Adobe-");
            case "psd" -> starts(head, 0x38, 0x42, 0x50, 0x53, 0, 1);
            case "xls", "doc" -> starts(head, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1);
            case "xlsx", "docx" -> starts(head, 0x50, 0x4b, 3, 4);
            // 打版软件的纸样：没有公开的固定文件头，只按扩展名收；/files/ 下不是图片的一律按下载发（Nginx、SignedFileFilter），浏览器不会打开它
            case "prj", "plt" -> true;
            default -> false;
        };
        if (!valid) throw invalid();
        if (extension.equals("xlsx") || extension.equals("docx")) verifyZip(file, extension);
        if (extension.equals("xls") || extension.equals("doc")) verifyOle(file, extension);
        if (Set.of("jpg", "jpeg", "png", "gif").contains(extension)) verifyRaster(file);
        if (extension.equals("webp")) verifyWebp(head);
        if (extension.equals("psd")) {
            if (head.length < 26) throw invalid();
            dimensions(unsignedInt(head, 18), unsignedInt(head, 14));
        }
    }

    private static void verifyRaster(Path file) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw invalid();
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try { reader.setInput(input, true, true); dimensions(reader.getWidth(0), reader.getHeight(0)); }
            catch (RuntimeException | IOException failure) { throw invalid(); }
            finally { reader.dispose(); }
        }
    }

    private static void verifyWebp(byte[] head) {
        if (head.length < 30) throw invalid();
        String chunk = new String(head, 12, 4, StandardCharsets.US_ASCII);
        long width, height;
        switch (chunk) {
            case "VP8X" -> { width = little24(head, 24) + 1; height = little24(head, 27) + 1; }
            case "VP8L" -> {
                if ((head[20] & 255) != 0x2f) throw invalid();
                width = 1 + ((head[21] & 255) | (head[22] & 0x3f) << 8);
                height = 1 + ((head[22] & 0xc0) >> 6 | (head[23] & 255) << 2 | (head[24] & 15) << 10);
            }
            case "VP8 " -> {
                if ((head[23] & 255) != 0x9d || (head[24] & 255) != 1 || (head[25] & 255) != 0x2a) throw invalid();
                width = ((head[26] & 255) | (head[27] & 255) << 8) & 0x3fff;
                height = ((head[28] & 255) | (head[29] & 255) << 8) & 0x3fff;
            }
            default -> throw invalid();
        }
        dimensions(width, height);
    }

    private static void dimensions(long width, long height) {
        if (width < 1 || height < 1 || width > 100_000 || height > 100_000 || width * height > 100_000_000)
            throw new BizException("图片像素尺寸过大，请缩小后上传");
    }

    private static void verifyZip(Path file, String extension) throws IOException {
        Set<String> names = new HashSet<>(); long total = 0;
        try (var zip = new ZipArchiveInputStream(Files.newInputStream(file))) {
            var buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (names.size() >= 256 || !names.add(name) || name.startsWith("/") || name.contains("\\")
                        || name.contains(":") || java.util.Arrays.asList(name.split("/")).contains("..")) throw invalid();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.startsWith("xl/externallinks/") || lower.contains("/activex/"))
                    throw new BizException("不支持含宏、外部工作簿或活动控件的文件");
                long expanded = 0; int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count; total += count;
                    if (expanded > MAX_ENTRY || total > MAX_EXPANDED) throw new BizException("压缩文件展开后过大，请拆分后上传");
                    if (expanded > 1024 * 1024 && zip.getCompressedCount() * 100L < expanded)
                        throw new BizException("压缩文件展开比例异常，请重新保存后上传");
                }
            }
        } catch (IllegalArgumentException | IOException failure) { throw invalid(); }
        if (!names.contains("[Content_Types].xml") || !names.contains(extension.equals("xlsx") ? "xl/workbook.xml" : "word/document.xml")) throw invalid();
    }

    private static void verifyOle(Path file, String extension) throws IOException {
        try (var system = new POIFSFileSystem(file.toFile(), true)) {
            var root = system.getRoot();
            if (!(extension.equals("doc") ? root.hasEntry("WordDocument") : root.hasEntry("Workbook") || root.hasEntry("Book"))) throw invalid();
            checkDirectory(root, 0, new int[1]);
        } catch (IllegalArgumentException | IOException failure) { throw invalid(); }
    }

    private static void checkDirectory(DirectoryEntry directory, int depth, int[] entries) {
        if (depth > 16) throw invalid();
        for (var entry : directory) {
            if (++entries[0] > 512) throw invalid();
            String name = entry.getName().toUpperCase(Locale.ROOT);
            if (name.equals("VBA") || name.contains("VBA_PROJECT") || name.equals("MACROS")) throw new BizException("不支持含宏的文件");
            if (entry instanceof DirectoryEntry child) checkDirectory(child, depth + 1, entries);
        }
    }

    private static boolean starts(byte[] head, int... prefix) {
        if (head.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((head[i] & 255) != prefix[i]) return false;
        return true;
    }
    private static boolean ascii(byte[] head, String prefix) { return new String(head, StandardCharsets.ISO_8859_1).startsWith(prefix); }
    private static long little24(byte[] bytes, int o) { return (bytes[o] & 255L) | (bytes[o + 1] & 255L) << 8 | (bytes[o + 2] & 255L) << 16; }
    private static long unsignedInt(byte[] bytes, int o) { return (bytes[o] & 255L) << 24 | (bytes[o + 1] & 255L) << 16 | (bytes[o + 2] & 255L) << 8 | (bytes[o + 3] & 255L); }
    private static BizException invalid() { return new BizException("文件内容与扩展名不匹配或文件已损坏，请重新保存后上传"); }
}
