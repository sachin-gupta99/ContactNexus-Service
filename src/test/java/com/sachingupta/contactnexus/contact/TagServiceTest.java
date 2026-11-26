package com.sachingupta.contactnexus.contact;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagServiceTest {

    @Test
    void normalizeTrimsCollapsesAndDedupesIgnoringCase() {
        assertThat(TagService.normalize(Arrays.asList("  Work ", "work", "Close   friends", "", null, "Family")))
                .containsExactly("Work", "Close friends", "Family");
        assertThat(TagService.normalize(null)).isEmpty();
    }

    @Test
    void resolveReusesExistingTagsAndCreatesOnlyMissingOnes() {
        TagRepository repo = mock(TagRepository.class);
        Tag work = Tag.builder().id(1L).ownerId(7L).name("Work").build();
        when(repo.findByOwnerAndLowerNames(eq(7L), anyCollection())).thenReturn(List.of(work));
        when(repo.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Tag> resolved = new TagService(repo).resolve(7L, List.of("work", "Family"));

        assertThat(resolved).containsOnlyKeys("work", "family");
        assertThat(resolved.get("work")).isSameAs(work); // existing spelling kept
        assertThat(resolved.get("family").getName()).isEqualTo("Family");
        verify(repo, times(1)).save(any(Tag.class));
    }
}
