package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.mapper.ActionRequestMapper;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionRequestServiceImplTest {
    @Mock private ActionRequestMapper mapper;
    private ActionRequestServiceImpl service;
    private final UUID id = UUID.fromString("b663ca73-bf5e-4fcf-b2e7-131f2611c4db");
    record Saved(long id, String no) { }

    @BeforeEach void before() {
        service = new ActionRequestServiceImpl(mapper, JsonMapper.builder().build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(8L, "test"), null, List.of()));
    }
    @AfterEach void after() { SecurityContextHolder.clearContext(); }

    @Test void hash_normalizesNumbersPropertyOrderAndActionId_butRetainsTargetAndArrayOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("clientActionId", id); first.put("qty", new BigDecimal("1.00")); first.put("items", List.of(2, 3));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("items", List.of(2, 3)); second.put("qty", BigDecimal.ONE); second.put("clientActionId", UUID.randomUUID());
        assertThat(service.hash("OP", 1L, first)).isEqualTo(service.hash("OP", 1L, second));
        assertThat(service.hash("OP", 2L, second)).isNotEqualTo(service.hash("OP", 1L, first));
        second.put("items", List.of(3, 2));
        assertThat(service.hash("OP", 1L, second)).isNotEqualTo(service.hash("OP", 1L, first));
    }

    @Test void replay_returnsOriginalResultWithoutRepeatingBusiness() {
        when(mapper.tryLock(anyLong())).thenReturn(true);
        Map<String, Integer> body = Map.of("qty", 5);
        when(mapper.lock(8L, "OP", id)).thenReturn(new ActionRequestMapper.Claim(1, service.hash("OP", 2, body), "{\"id\":31,\"no\":\"RC31\"}"));
        AtomicInteger calls = new AtomicInteger();
        Saved saved = service.execute("OP", 2, id, body, Saved.class, () -> { calls.incrementAndGet(); return new Saved(99, "new"); });
        assertThat(saved).isEqualTo(new Saved(31, "RC31"));
        assertThat(calls).hasValue(0);
        verify(mapper, never()).complete(eq(1L), anyString());
    }

    @Test void sameActionDifferentBody_isConflictAndDoesNotRunBusiness() {
        when(mapper.tryLock(anyLong())).thenReturn(true);
        when(mapper.lock(8L, "OP", id)).thenReturn(new ActionRequestMapper.Claim(1, service.hash("OP", 2, Map.of("qty", 5)), "{}"));
        assertThatThrownBy(() -> service.execute("OP", 2, id, Map.of("qty", 6), Saved.class, () -> { throw new AssertionError(); }))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(1002);
    }

    @Test void claim_runsOnceAndCompletesWithOriginalResponse() {
        when(mapper.tryLock(anyLong())).thenReturn(true);
        Map<String, Integer> body = Map.of("qty", 5);
        String hash = service.hash("OP", 2, body);
        when(mapper.claim(8L, "OP", id, hash)).thenReturn(1);
        when(mapper.lock(8L, "OP", id)).thenReturn(new ActionRequestMapper.Claim(1, hash, null));
        when(mapper.complete(eq(1L), anyString())).thenReturn(1);
        assertThat(service.execute("OP", 2, id, body, Saved.class, () -> new Saved(31, "RC31"))).isEqualTo(new Saved(31, "RC31"));
        verify(mapper).complete(1L, "{\"id\":31,\"no\":\"RC31\"}");
    }

    @Test void missingActionId_rejectedBeforeClaim() {
        assertThatThrownBy(() -> service.execute("OP", 2, null, Map.of(), Saved.class, () -> new Saved(31, "RC31")))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
    }
}
