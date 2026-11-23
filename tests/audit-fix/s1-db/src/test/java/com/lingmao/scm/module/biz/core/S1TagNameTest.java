package com.lingmao.scm.module.biz.core;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S1TagNameTest {
    @Test
    void nfkcUnicodeStripSingleHashAndCanonicalDeduplicateWithoutLosingDisplay() {
        assertThat(TagName.of("\u00A0＃ ＡＢＣ\u3000").display()).isEqualTo("ABC");
        assertThat(TagName.of("\u00A0＃ ＡＢＣ\u3000").canonical()).isEqualTo("abc");
        assertThat(TagName.of("##蓝色").display()).isEqualTo("#蓝色");
        for (String raw : List.of("ABC", "##蓝色", "###保留")) {
            TagName stored = TagName.of(raw);
            // 已有 pill 使用一个 transport # + display，不能把含 # 的显示名再当原始输入剥一轮。
            assertThat(TagName.of("#" + stored.display())).isEqualTo(stored);
        }
        assertThat(TagName.distinct(List.of(" #AbC", "ＡＢＣ", "蓝色")))
                .extracting(TagName::display).containsExactly("AbC", "蓝色");
        assertThat(TagName.prefix(null)).isEmpty();
        assertThat(TagName.prefix(" # ")).isEmpty();
    }

    @Test
    @ResourceLock("default-locale")
    void canonicalUsesRootEvenUnderTurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(TagName.of("Iİ").canonical()).isEqualTo("ii\u0307");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void displayCountsUnicodeCodePointsAndPreservesEmojiJoiners() {
        assertThat(TagName.of("😀".repeat(64)).display().codePointCount(0, 128)).isEqualTo(64);
        assertThatThrownBy(() -> TagName.of("😀".repeat(65))).isInstanceOf(BizException.class);
        assertThat(TagName.of("👩\u200D💻").display()).isEqualTo("👩\u200D💻");
        assertThat(TagName.of("İ".repeat(64)).canonical().codePointCount(0, 128)).isEqualTo(128);
    }

    @Test
    void controlsBidiCommasMalformedSurrogatesAndOversizedRequestsAreRejected() {
        for (String bad : List.of("", " # ", "a,b", "a，b", "a\nb", "\tABC", "ABC\r", "a\u0085b",
                "a\u202Ab", "a\u202Eb", "a\u2066b", "a\u2069b", "a\uD800b", "a\uDC00b")) {
            assertThatThrownBy(() -> TagName.of(bad)).as("invalid tag input length=%s", bad.length()).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> TagName.of(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> TagName.of("x".repeat(1025))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> TagName.distinct(java.util.Collections.nCopies(31, "same"))).isInstanceOf(BizException.class);
    }

    @Test
    void actualSignedHashKeysAreSortedDeduplicatedAndNamespaceBound() {
        var first = new TagNameLocks(null, new DeploymentNamespace("tag-alpha"));
        var second = new TagNameLocks(null, new DeploymentNamespace("tag-beta"));
        List<String> names = List.of("红色", "blue", "amber", "blue", "same", "😀", "tail");
        List<Long> keys = first.keys(names);
        var reversed = new ArrayList<>(names);
        java.util.Collections.reverse(reversed);
        assertThat(keys).isSorted().doesNotHaveDuplicates().hasSize(names.size() - 1);
        assertThat(keys).isEqualTo(first.keys(reversed));
        assertThat(first.keys(List.of("same"))).isNotEqualTo(second.keys(List.of("same")));
        assertThat(keys).contains(TagNameLocks.key("tag-alpha", "blue"));
        assertThatThrownBy(() -> first.acquire(List.of("same"))).isInstanceOf(IllegalStateException.class);
    }
}
