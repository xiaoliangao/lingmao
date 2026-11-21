package com.lingmao.scm.common.image;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ThumbnailsTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** 规则：图本身的问题（认不出、截断）是 Unreadable，报给用户；文件打不开这种磁盘的问题还是普通 IOException，走服务器出错 */
    @Test
    void create_imageProblemsVsDiskProblems() throws IOException {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Path truncated = Files.write(dir.resolve("半截.png"), Arrays.copyOf(png.toByteArray(), 120));
        Path garbage = Files.write(dir.resolve("乱码.png"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        Path target = dir.resolve("s.png");

        assertThat(catchThrowableOfType(Thumbnails.Unreadable.class, () -> Thumbnails.create(truncated, target, "png")))
                .hasMessageContaining("图片打不开");
        assertThat(catchThrowableOfType(Thumbnails.Unreadable.class, () -> Thumbnails.create(garbage, target, "png")))
                .hasMessageContaining("图片打不开");
        IOException missing = catchThrowableOfType(IOException.class, () -> Thumbnails.create(dir.resolve("没有.png"), target, "png"));
        assertThat(missing).isNotNull().isNotInstanceOf(Thumbnails.Unreadable.class);
        assertThat(Files.exists(target)).isFalse();
    }
}
