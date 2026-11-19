package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.techpack.dto.PackFileForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TechPackFilesTest {

    @Mock
    private AttachmentService attachmentService;
    @InjectMocks
    private TechPackFiles files;

    /**
     * 规则（RFC 011 §2.2）：每组收的类型不一样（工艺单不收 .prj，导入的 BOM 表只收 Excel），按地址里的扩展名认、签名参数不算；
     * 一组超过 20 个也拦。先全部检查完再写：有一个不对，三组都不动。files 是 null（老页面没传）什么都不动
     */
    @Test
    void replace_checksEveryGroupBeforeWriting() {
        files.replace(9L, null);
        verifyNoInteractions(attachmentService);

        assertThatThrownBy(() -> files.replace(9L, List.of(form("PATTERN", "/files/a/p.prj"), form("CRAFT_SHEET", "/files/a/p.prj"))))
                .isInstanceOf(BizException.class).hasMessageContaining("工艺单不收 .prj");
        assertThatThrownBy(() -> files.replace(9L, List.of(form("BOM_SOURCE", "/files/a/b.pdf"))))
                .isInstanceOf(BizException.class).hasMessageContaining("不收 .pdf");
        assertThatThrownBy(() -> files.replace(9L, List.of(form("SIZE", "/files/a/b.xlsx"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> files.replace(9L, Collections.nCopies(21, form("CRAFT_SHEET", "/files/a/c.pdf"))))
                .isInstanceOf(BizException.class).hasMessageContaining("最多 20 个");
        verify(attachmentService, never()).replace(any(), anyLong(), anyString(), any());

        files.replace(9L, List.of(form("CRAFT_SHEET", "/files/a/c.XLSX?expires=1&sig=x"), form("PATTERN", "/files/a/p.prj"), form("PATTERN", "/files/a/p.PLT")));
        verify(attachmentService).replace(eq(BizType.TECH_PACK), eq(9L), eq("CRAFT_SHEET"), argThat(l -> l.size() == 1));
        verify(attachmentService).replace(eq(BizType.TECH_PACK), eq(9L), eq("PATTERN"), argThat(l -> l.size() == 2));
        verify(attachmentService).replace(eq(BizType.TECH_PACK), eq(9L), eq("BOM_SOURCE"), argThat(List::isEmpty));
    }

    @Test
    void extension_ignoresSignatureAndCase() {
        assertThat(TechPackFiles.extension("/files/2026/11/工艺单.XLS?expires=1&sig=a.b")).isEqualTo("xls");
        assertThat(TechPackFiles.extension("/files/2026/11/noext")).isEmpty();
        assertThat(TechPackFiles.extension(null)).isEmpty();
    }

    private static PackFileForm form(String category, String url) {
        PackFileForm f = new PackFileForm();
        f.setCategory(category);
        f.setUrl(url);
        f.setName(url.substring(url.lastIndexOf('/') + 1));
        return f;
    }
}
