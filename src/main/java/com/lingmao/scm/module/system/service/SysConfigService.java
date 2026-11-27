package com.lingmao.scm.module.system.service;

/** sys_config 的读写（一个键一行，值是 JSON），给别的域用：别的域不直接碰 ConfigMapper */
public interface SysConfigService {

    /** 值的 JSON 文本；没有这一行是 null */
    String get(String key);

    /** 存值（JSON 文本），记上当前登录的人 */
    void put(String key, String json);
}
