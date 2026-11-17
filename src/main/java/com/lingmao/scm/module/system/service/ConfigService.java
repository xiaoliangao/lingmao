package com.lingmao.scm.module.system.service;

import java.util.Optional;

/**
 * 系统设置（sys_config，数据库设计 §3.9）：设置页里非字典、非审批规则的参数，值存成 JSON。
 * 一期只有资料包用：工艺指示各段的默认文字 techpack.craft_defaults、新建资料包的默认包装方式 techpack.packing_mode。
 * 密钥不放这里（PRD §8）
 */
public interface ConfigService {

    /** 取一项；没设过返回 empty。type 是值的 Java 类型：文字用 String.class，{段代码: 文字} 用 Map.class */
    <T> Optional<T> get(String key, Class<T> type);

    /** 存一项（没有就新增），值转成 JSON 存；修改人取当前登录用户 */
    void put(String key, Object value);
}
