package com.lingmao.scm.module.system.service;

/** Durable marker exists before the first byte is written, including for interrupted uploads. */
public interface UploadCatalog {
    void begin(UploadStore.Slot slot);
    void ready(UploadStore.Slot slot, UploadStore.Stored stored);
    void failed(UploadStore.Slot slot);
}
