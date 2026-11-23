package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** No server or database: the recorded body must retain the exact bytes chosen before HTTP send. */
class S2SampleEvidenceTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void laterBoundaryCorrectionCannotRewriteTheRejectedRequestEvidence() {
        Map<String, Object> photo = new LinkedHashMap<>(Map.of("name", "original.png"));
        List<Object> photos = new ArrayList<>(List.of(photo));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("opinion", "界".repeat(501));
        body.put("photos", photos);
        body.put("entityId", "9223372036854775806");
        body.put("amount", "12345678901234567890.012345678900");
        body.put("sequence", new BigInteger("9223372036854775808"));
        var rejected = S2SampleFixture.freezeBody(json, body);
        String sentJson = rejected.json();

        body.put("opinion", "界".repeat(500));
        body.put("entityId", "1");
        body.put("amount", "0.01");
        body.put("sequence", BigInteger.ONE);
        photo.put("name", "changed.png");
        photos.add(Map.of("name", "added.png"));
        var corrected = S2SampleFixture.freezeBody(json, body);
        body.clear();
        photos.clear();
        photo.clear();

        assertThat(rejected.json()).isEqualTo(sentJson);
        assertThat(rejected.snapshot()).isEqualTo(json.readTree(sentJson));
        assertThat(rejected.snapshot().path("opinion").stringValue()).hasSize(501);
        assertThat(rejected.snapshot().path("photos").size()).isOne();
        assertThat(rejected.snapshot().path("photos").get(0).path("name").stringValue()).isEqualTo("original.png");
        assertThat(rejected.snapshot().path("entityId").stringValue()).isEqualTo("9223372036854775806");
        assertThat(rejected.snapshot().path("amount").stringValue()).isEqualTo("12345678901234567890.012345678900");
        assertThat(rejected.snapshot().path("sequence").toString()).isEqualTo("9223372036854775808");
        assertThat(corrected.snapshot().path("opinion").stringValue()).hasSize(500);
        assertThat(corrected.snapshot().path("photos").size()).isEqualTo(2);
        assertThat(corrected.snapshot().path("photos").get(0).path("name").stringValue()).isEqualTo("changed.png");
        assertThat(corrected.snapshot()).isEqualTo(json.readTree(corrected.json()));
        assertThat(corrected.json()).isNotEqualTo(sentJson);
    }

    @Test
    void absentBodyRemainsAbsentInPublisherAndEvidence() {
        var frozen = S2SampleFixture.freezeBody(json, null);
        assertThat(frozen.json()).isNull();
        assertThat(frozen.snapshot()).isNull();
    }
}
