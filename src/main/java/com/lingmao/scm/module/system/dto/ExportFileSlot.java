package com.lingmao.scm.module.system.dto;

import java.nio.file.Path;
import java.util.UUID;

public record ExportFileSlot(long jobId, UUID token, Path partial, Path complete) {}
