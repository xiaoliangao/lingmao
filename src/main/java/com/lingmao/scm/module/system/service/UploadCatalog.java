package com.lingmao.scm.module.system.service;

/** 写第一个字节之前就有持久的登记，上传中断也找得到。 */
public interface UploadCatalog {
    void begin(UploadStore.Slot slot);
    void ready(UploadStore.Slot slot, UploadStore.Stored stored);
    void failed(UploadStore.Slot slot);
}
