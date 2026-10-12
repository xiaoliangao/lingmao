package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.FileVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServiceImplTest {

    /** JUnit 给每个测试一个临时目录，测完自动删掉 */
    @TempDir
    Path tempDir;

    /** 规则：存到 上传目录/年/月/日/随机名.扩展名，返回 /files/ 开头的地址 */
    @Test
    void upload_ok_savesUnderDatePath() throws Exception {
        FileServiceImpl service = new FileServiceImpl(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "面料.PNG", "image/png", new byte[]{1, 2, 3});

        FileVO vo = service.upload(file);

        assertThat(vo.url()).matches("/files/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(vo.name()).isEqualTo("面料.PNG");
        assertThat(vo.size()).isEqualTo(3);
        Path saved = tempDir.resolve(vo.url().substring("/files/".length()));
        assertThat(Files.readAllBytes(saved)).containsExactly(1, 2, 3);
    }

    /** 规则：不在白名单里的扩展名（比如 html）不收 */
    @Test
    void upload_htmlFile_rejected() {
        FileServiceImpl service = new FileServiceImpl(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "x.html", "text/html", "<script>".getBytes());

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(BizException.class)
                .hasMessage("不支持的文件类型: x.html");
    }
}
