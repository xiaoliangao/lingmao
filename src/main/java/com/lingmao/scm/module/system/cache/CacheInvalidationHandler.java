package com.lingmao.scm.module.system.cache;

import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.common.jobs.OutboxWork;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public record CacheInvalidationHandler(SharedCache cache, JsonMapper json) implements OutboxHandler {
    @Override public String kind() { return "CACHE_INVALIDATE"; }
    @Override public void execute(OutboxWork work, String payload) {
        work.checkpoint(); cache.invalidate(json.readValue(payload, String.class));
    }
}
