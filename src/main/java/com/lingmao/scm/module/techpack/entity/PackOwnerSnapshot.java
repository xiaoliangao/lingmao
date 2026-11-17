package com.lingmao.scm.module.techpack.entity;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.techpack.dto.PackOwner;

import java.util.List;

/** 生效时冻结的展示上下文；旧版本的 NULL 不回填成伪历史（RFC010 §4.2）。 */
public record PackOwnerSnapshot(int schemaVersion, PackOwner owner, List<String> sizes, String baseSize,
                                List<ColorOption> colors) {
    public PackOwnerSnapshot {
        sizes = List.copyOf(sizes);
        colors = List.copyOf(colors);
    }
}
